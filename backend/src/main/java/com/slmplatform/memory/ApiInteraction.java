package com.slmplatform.memory;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document(collection = "api_interactions")
public record ApiInteraction(@Id String id, String sessionId, String apiName, String requestPayload,
        String responsePayload, Instant timestamp) {
}
