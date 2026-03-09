package com.slmplatform.memory;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.List;

@Document(collection = "agent_long_term_memory")
public record MemoryChunk(
    @Id String id, 
    String content, 
    List<Double> embedding, 
    String agentId, 
    String sessionId
) {}