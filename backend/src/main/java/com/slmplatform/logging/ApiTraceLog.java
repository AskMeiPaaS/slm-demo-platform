package com.slmplatform.logging;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document(collection = "api_trace_logs")
public record ApiTraceLog(
        @Id String id, String sessionId, String endpoint, String method,
        String requestPayload, String responsePayload,
        int statusCode, long durationMs, Instant timestamp, String errorDetails) {
}
