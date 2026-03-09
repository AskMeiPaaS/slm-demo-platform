package com.slmplatform.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slmplatform.ai.VoyageAiService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;

@Component
public class MovieSearchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(MovieSearchTool.class);
    private final MongoTemplate mongoTemplate;
    private final VoyageAiService voyageAiService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String rerankerModel;

    public MovieSearchTool(MongoTemplate mongoTemplate,
            VoyageAiService voyageAiService,
            @Value("${voyageai.reranker-model:rerank-2-lite}") String rerankerModel) {
        this.mongoTemplate = mongoTemplate;
        this.voyageAiService = voyageAiService;
        this.rerankerModel = rerankerModel;
    }

    @Override
    public String getName() {
        return "search_movie_data";
    }

    @Override
    public String getDescription() {
        return "Vector searches movie full plots to answer questions about movies. Required parameter: 'query'.";
    }

    @Override
    public String execute(String parametersJson, String sessionId) {
        try {
            Map<String, String> params = objectMapper.readValue(parametersJson,
                    new TypeReference<Map<String, String>>() {
                    });
            String query = params.get("query");
            if ((query == null || query.isBlank()) && !params.isEmpty()) {
                // LLM hallucinated the parameter name (e.g. "key" instead of "query"), fallback
                // to the first available value
                query = params.values().iterator().next();
            }

            if (query == null || query.isBlank()) {
                return "{ \"error\": \"Query parameter is missing.\" }";
            }

            // 1. Embed the incoming User Query using Voyage AI
            List<Double> queryEmbedding = voyageAiService.getEmbedding(query, sessionId);
            if (queryEmbedding.isEmpty()) {
                return "Error: Failed to generate embedding for the search query.";
            }

            List<Document> combinedDocuments = new ArrayList<>();

            // 2. Vector Search the Movies Collection (Targeting fullplot)
            combinedDocuments.addAll(vectorSearchCollection("movies", queryEmbedding, "fullplot"));

            if (combinedDocuments.isEmpty()) {
                return "No relevant movie data found in the database.";
            }

            // Extract fullplot for the reranker
            List<String> fullplots = combinedDocuments.stream()
                    .map(doc -> doc.getString("fullplot") != null ? doc.getString("fullplot") : "")
                    .toList();

            // 4. Pass combined search results through the Voyage Reranker & Return Top N
            // Indices
            List<Integer> topIndices = voyageAiService.getTopRerankedDocumentIndices(query, fullplots, rerankerModel,
                    sessionId);
            if (topIndices.isEmpty()) {
                return "No highly relevant movie data found.";
            }

            // 5. Build context out of `title`, JSON metadata, and `plot` mapping the top
            // indices
            List<String> docsContext = new ArrayList<>();
            for (int idx : topIndices) {
                Document doc = combinedDocuments.get(idx);
                String title = doc.getString("title");
                String plot = doc.getString("plot");

                List<String> genres = doc.getList("genres", String.class);
                List<String> cast = doc.getList("cast", String.class);
                List<String> directors = doc.getList("directors", String.class);
                Integer year = doc.getInteger("year");

                Document imdb = doc.get("imdb", Document.class);
                Object imdbRating = imdb != null ? imdb.get("rating") : null;

                log.info("[MovieSearchTool] Reranker selected Top-K Match: '{}' (Index: {})", title, idx);

                if (plot != null && !plot.isBlank()) {
                    StringBuilder recordBuilder = new StringBuilder("[RECORD]\n");
                    if (title != null)
                        recordBuilder.append("Movie Title: ").append(title).append("\n");
                    if (year != null)
                        recordBuilder.append("Year: ").append(year).append("\n");
                    if (genres != null && !genres.isEmpty())
                        recordBuilder.append("Genres: ").append(String.join(", ", genres)).append("\n");
                    if (cast != null && !cast.isEmpty())
                        recordBuilder.append("Cast: ").append(String.join(", ", cast)).append("\n");
                    if (directors != null && !directors.isEmpty())
                        recordBuilder.append("Directors: ").append(String.join(", ", directors)).append("\n");
                    if (imdbRating != null)
                        recordBuilder.append("IMDB Rating: ").append(imdbRating).append("\n");

                    recordBuilder.append("Description: ").append(plot);
                    docsContext.add(recordBuilder.toString());
                }
            }

            return docsContext.isEmpty() ? "Detailed plot data missing for best matches."
                    : String.join("\n\n---\n\n", docsContext);

        } catch (Exception e) {
            return "{ \"error\": \"Search failed due to: " + e.getMessage() + "\" }";
        }
    }

    private List<Document> vectorSearchCollection(String collectionName, List<Double> embedding, String textField) {
        try {
            // Mongo Atlas Vector Search pipeline syntax
            String vectorSearchJson = String.format(
                    """
                            { "$vectorSearch": { "index": "vector_index", "path": "embedding", "queryVector": %s, "numCandidates": 50, "limit": 10 } }
                            """,
                    embedding.toString());

            AggregationOperation vectorSearchOperation = context -> context
                    .getMappedObject(Document.parse(vectorSearchJson));
            Aggregation aggregation = Aggregation.newAggregation(vectorSearchOperation);

            log.info("[MovieSearchTool] Commencing $vectorSearch on '{}' collection against field '{}'...",
                    collectionName, textField);
            List<Document> results = mongoTemplate.aggregate(aggregation, collectionName, Document.class)
                    .getMappedResults();
            log.info("[MovieSearchTool] $vectorSearch completed successfully. Retrieved {} candidate matches.",
                    results.size());
            return results;
        } catch (Exception e) {
            log.error(
                    "[MovieSearchTool] $vectorSearch failed! Ensure Atlas Vector Search index 'vector_index' is built on {}. Error: {}",
                    collectionName, e.getMessage(), e);
            // Fails safe if the vector search index isn't properly created yet in Mongo
            return new ArrayList<>();
        }
    }
}