package com.slmplatform.seeder;

import com.slmplatform.ai.VoyageAiService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class DataSeederService {

    private final MongoTemplate mongoTemplate;
    private final VoyageAiService voyageAiService;
    private final String sampleDataPath;
    private final int embeddingDimension;

    public DataSeederService(MongoTemplate mongoTemplate,
            VoyageAiService voyageAiService,
            @Value("${sample-data.path:./sample-data}") String sampleDataPath,
            @Value("${voyageai.embedding-dimension:1024}") int embeddingDimension) {
        this.mongoTemplate = mongoTemplate;
        this.voyageAiService = voyageAiService;
        this.sampleDataPath = sampleDataPath;
        this.embeddingDimension = embeddingDimension;
    }

    public Map<String, Object> seedAndEmbedData() {
        Map<String, Object> report = new HashMap<>();
        try {
            // Process a capped batch of 20 records each to respect API rate limits during
            // demo
            int moviesProcessed = processCollectionFile("movies.json", "movies", "fullplot", 20);

            report.put("status", "success");
            report.put("movies_embedded", moviesProcessed);
        } catch (Exception e) {
            report.put("status", "error");
            report.put("message", e.getMessage());
        }
        return report;
    }

    public void seedAndEmbedDataStream(SseEmitter emitter) {
        try {
            sendProgress(emitter, "Starting data pipeline...");

            // Drop database first
            sendProgress(emitter, "Dropping existing database...");
            mongoTemplate.getDb().drop();

            // Process movies
            sendProgress(emitter, "Commencing data insertion for movies collection...");
            int moviesProcessed = processCollectionFileStream("movies.json", "movies", "fullplot", emitter);

            sendProgress(emitter,
                    String.format("Creating Vector Search Index on %d movie embeddings...", moviesProcessed));
            createVectorIndexOptional("movies");

            Map<String, Object> report = new HashMap<>();
            report.put("status", "success");
            report.put("movies_embedded", moviesProcessed);
            emitter.send(SseEmitter.event().data(report));
            emitter.complete();
        } catch (Exception e) {
            try {
                Map<String, Object> errorReport = new HashMap<>();
                errorReport.put("status", "error");
                errorReport.put("message", e.getMessage());
                emitter.send(SseEmitter.event().data(errorReport));
                emitter.completeWithError(e);
            } catch (Exception ex) {
                // Ignore
            }
        }
    }

    private void sendProgress(SseEmitter emitter, String message) throws Exception {
        Map<String, String> statusMap = new HashMap<>();
        statusMap.put("status", "progress");
        statusMap.put("message", message);
        emitter.send(SseEmitter.event().data(statusMap));
    }

    private int processCollectionFile(String fileName, String collectionName, String embedField, int limit)
            throws Exception {
        Path filePath = Paths.get(sampleDataPath, fileName);

        if (!Files.exists(filePath)) {
            System.out.println("File not found: " + filePath.toAbsolutePath());
            return 0;
        }

        // Drop collection to allow fresh re-seeding from UI
        mongoTemplate.dropCollection(collectionName);
        System.out.println("Embedding " + collectionName + " using Voyage AI...");

        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(filePath.toFile()))) {
            String line;
            while ((line = br.readLine()) != null && count < limit) {
                Document doc = Document.parse(line); // Safely parses Mongo Extended JSON
                String targetText = doc.getString(embedField);

                if (targetText != null && !targetText.isBlank()) {
                    // Call Voyage AI to get embeddings
                    List<Double> embedding = voyageAiService.getEmbedding(targetText, "system-seeder");

                    if (!embedding.isEmpty()) {
                        doc.put("embedding", embedding);
                        mongoTemplate.save(doc, collectionName);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private int processCollectionFileStream(String fileName, String collectionName, String embedField,
            SseEmitter emitter) throws Exception {
        Path filePath = Paths.get(sampleDataPath, fileName);

        if (!Files.exists(filePath)) {
            sendProgress(emitter, "File not found: " + filePath.toAbsolutePath());
            return 0;
        }

        int count = 0;
        List<Document> batchDocs = new ArrayList<>();
        List<String> batchTexts = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                Document doc = Document.parse(line);
                String targetText = doc.getString(embedField);

                if (targetText != null && !targetText.isBlank()) {
                    batchDocs.add(doc);
                    batchTexts.add(targetText);

                    if (batchTexts.size() >= 120) {
                        count += processBatch(batchDocs, batchTexts, collectionName, emitter, count);
                        batchDocs.clear();
                        batchTexts.clear();
                    }
                }
            }
            if (!batchTexts.isEmpty()) {
                count += processBatch(batchDocs, batchTexts, collectionName, emitter, count);
            }
        }
        sendProgress(emitter, String.format("Finished inserting %d %s into MongoDB.", count, collectionName));
        return count;
    }

    private int processBatch(List<Document> batchDocs, List<String> batchTexts, String collectionName,
            SseEmitter emitter, int count) throws Exception {
        List<List<Double>> embeddings = voyageAiService.getEmbeddings(batchTexts, "system-seeder");
        if (!embeddings.isEmpty() && embeddings.size() == batchTexts.size()) {
            for (int i = 0; i < batchDocs.size(); i++) {
                batchDocs.get(i).put("embedding", embeddings.get(i));
            }
            mongoTemplate.insert(batchDocs, collectionName);

            int newCount = count + batchDocs.size();
            if (newCount % 2400 == 0 || newCount % 1200 == 0) {
                sendProgress(emitter, String.format("Buffered %d %s into MongoDB...", newCount, collectionName));
            }
            return batchDocs.size();
        } else {
            sendProgress(emitter, String.format("Warning: Dropped a batch of %d records due to Voyage AI failure.",
                    batchTexts.size()));
            return 0;
        }
    }

    private void createVectorIndexOptional(String collectionName) {
        try {
            String indexDefinition = String.format("""
                        {
                          "createSearchIndexes": "%s",
                          "indexes": [
                            {
                              "name": "vector_index",
                              "type": "vectorSearch",
                              "definition": {
                                "fields": [
                                  {
                                    "type": "vector",
                                    "path": "embedding",
                                    "numDimensions": %d,
                                    "similarity": "cosine"
                                  }
                                ]
                              }
                            }
                          ]
                        }
                    """, collectionName, embeddingDimension);

            mongoTemplate.executeCommand(indexDefinition);
        } catch (Exception e) {
            System.err.println("Failed to create vector index: " + e.getMessage());
        }
    }
}