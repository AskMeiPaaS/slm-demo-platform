package com.slmplatform.memory;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document(collection = "agent_short_term_memory")
public record ChatMemory(@Id String id, String sessionId, String role, String content, Instant timestamp) {
}
