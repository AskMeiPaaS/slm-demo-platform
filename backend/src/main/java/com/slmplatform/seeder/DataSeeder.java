package com.slmplatform.seeder;

import com.slmplatform.ai.VoyageAiService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private final MongoTemplate mongoTemplate;
    private final VoyageAiService voyageAiService;
    private final String sampleDataPath;
    private final boolean enabled;
    private final int embeddingDimension;

    public DataSeeder(MongoTemplate mongoTemplate,
            VoyageAiService voyageAiService,
            @Value("${sample-data.path:./sample-data}") String sampleDataPath,
            @Value("${sample-data.enabled:false}") boolean enabled,
            @Value("${voyageai.embedding-dimension:1024}") int embeddingDimension) {
        this.mongoTemplate = mongoTemplate;
        this.voyageAiService = voyageAiService;
        this.sampleDataPath = sampleDataPath;
        this.enabled = enabled;
        this.embeddingDimension = embeddingDimension;
    }

    @Override
    public void run(String... args) {
        if (!enabled)
            return;

        try {
            seedCollection("movies.json", "movies", "fullplot");
            createVectorIndexOptional("movies");
        } catch (Exception e) {
            System.err.println("Failed to seed sample data: " + e.getMessage());
        }
    }

    private void seedCollection(String fileName, String collectionName, String embedField) throws Exception {
        Path filePath = Paths.get(sampleDataPath, fileName);

        // Only seed if the file exists and the collection is empty to prevent infinite
        // re-embedding
        if (Files.exists(filePath) && mongoTemplate.getCollection(collectionName).countDocuments() == 0) {
            System.out.println("Seeding and embedding data for collection: " + collectionName);

            try (BufferedReader br = new BufferedReader(new FileReader(filePath.toFile()))) {
                String line;
                int count = 0;

                // Read line-by-line (Max 100 for demo to respect Voyage rate limits)
                while ((line = br.readLine()) != null && count < 100) {
                    Document doc = Document.parse(line);
                    String targetText = doc.getString(embedField);

                    if (targetText != null && !targetText.isBlank()) {
                        // 1. Fetch Voyage Embedding
                        List<Double> embedding = voyageAiService.getEmbedding(targetText, "data-seeder");

                        // 2. Append embedding field to the document
                        if (!embedding.isEmpty()) {
                            doc.put("embedding", embedding);
                        }
                    }
                    // 3. Save back into the same MongoDB collection
                    mongoTemplate.save(doc, collectionName);
                    count++;
                }
            }
            System.out.println("Finished seeding " + collectionName);
        }
    }

    private void createVectorIndexOptional(String collectionName) {
        try {
            System.out.println("Ensuring vector_index exists on collection: " + collectionName);
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
            System.out.println("Successfully submitted search index creation command for " + collectionName);
        } catch (Exception e) {
            System.err.println(
                    "Failed to create vector index (you may safely ignore this if the index already exists or you are not using MongoDB Atlas): "
                            + e.getMessage());
        }
    }
}