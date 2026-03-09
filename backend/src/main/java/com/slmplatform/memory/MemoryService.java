package com.slmplatform.memory;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private final ChatMemoryRepository chatRepository;
    private final MemoryChunkRepository chunkRepository;
    private final ApiInteractionRepository apiInteractionRepository;
    private final MongoTemplate mongoTemplate;

    public MemoryService(ChatMemoryRepository chatRepository, MemoryChunkRepository chunkRepository,
            ApiInteractionRepository apiInteractionRepository, MongoTemplate mongoTemplate) {
        this.chatRepository = chatRepository;
        this.chunkRepository = chunkRepository;
        this.apiInteractionRepository = apiInteractionRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public void saveChatMessage(String sessionId, String role, String content) {
        chatRepository.save(new ChatMemory(null, sessionId, role, content, Instant.now()));
    }

    public void saveLongTermMemory(String content, List<Double> embedding, String agentId, String sessionId) {
        if (embedding != null && !embedding.isEmpty()) {
            chunkRepository.save(new MemoryChunk(null, content, embedding, agentId, sessionId));
        }
    }

    // New method to save API request/response pairs
    public void saveApiInteraction(String sessionId, String apiName, String requestPayload, String responsePayload) {
        apiInteractionRepository
                .save(new ApiInteraction(null, sessionId, apiName, requestPayload, responsePayload, Instant.now()));
    }

    public void clearSessionMemory(String sessionId) {
        chatRepository.deleteBySessionId(sessionId);
        chunkRepository.deleteBySessionId(sessionId);
    }

    public String getFormattedChatHistory(String sessionId) {
        List<ChatMemory> recent = getRawChatHistory(sessionId);
        return recent.isEmpty() ? "No previous history."
                : recent.stream().map(m -> m.role().toUpperCase() + ": " + m.content())
                        .collect(Collectors.joining("\n"));
    }

    public List<ChatMemory> getRawChatHistory(String sessionId) {
        List<ChatMemory> recent = chatRepository.findTop10BySessionIdOrderByTimestampDesc(sessionId);
        Collections.reverse(recent);
        return recent;
    }

    public List<MemoryChunk> retrieveVectorContext(List<Double> queryEmbedding, String agentId) {
        if (queryEmbedding == null || queryEmbedding.isEmpty())
            return Collections.emptyList();

        try {
            String vectorSearchJson = String.format(
                    """
                            { "$vectorSearch": { "index": "vector_index", "path": "embedding", "queryVector": %s, "numCandidates": 50, "limit": 5, "filter": { "agentId": "%s" } } }
                            """,
                    queryEmbedding.toString(), agentId);

            AggregationOperation vectorSearchOperation = context -> context
                    .getMappedObject(org.bson.Document.parse(vectorSearchJson));
            Aggregation aggregation = Aggregation.newAggregation(vectorSearchOperation);

            log.info("[MemoryService] Commencing $vectorSearch on 'agent_long_term_memory' for agent: {}", agentId);
            List<MemoryChunk> results = mongoTemplate
                    .aggregate(aggregation, "agent_long_term_memory", MemoryChunk.class).getMappedResults();
            log.info("[MemoryService] $vectorSearch completed successfully. Retrieved {} contextual memory chunks.",
                    results.size());

            return results;
        } catch (Exception e) {
            log.error(
                    "[MemoryService] $vectorSearch failed! Ensure index 'vector_index' is accurately configured on your MongoDB cluster. Error: {}",
                    e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}