package com.slmplatform.logging;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document(collection = "external_api_logs")
public record ExternalApiLog(
                @Id String id,
                String sessionId,
                String provider,
                String endpoint,
                String requestPayload,
                String responsePayload,
                int statusCode,
                long durationMs,
                Instant timestamp,
                String errorDetails) {
}
