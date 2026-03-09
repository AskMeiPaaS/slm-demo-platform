package com.slmplatform.logging;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;

@Service
public class ExternalApiLoggingService {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiLoggingService.class);
    private final ExternalApiLogRepository repository;

    public ExternalApiLoggingService(ExternalApiLogRepository repository) {
        this.repository = repository;
    }

    @Async
    public void logApiCall(String provider, String endpoint, String requestPayload, String responsePayload,
            int statusCode, long durationMs, String errorDetails, String sessionId) {

        log.info("[{}] {} - Status: {}, Duration: {}ms", provider, endpoint, statusCode, durationMs);

        String loggableResponse = responsePayload != null ? responsePayload : "null";
        if (loggableResponse.length() > 500) {
            loggableResponse = loggableResponse.substring(0, 500) + "... [TRUNCATED]";
        }
        log.debug("[{}] Request: {}", provider, requestPayload);
        log.info("[{}] Response: {}", provider, loggableResponse);

        String finalReqPayload = requestPayload != null && requestPayload.length() > 50000
                ? requestPayload.substring(0, 50000) + "... [TRUNCATED]"
                : requestPayload;
        String finalResPayload = responsePayload != null && responsePayload.length() > 50000
                ? responsePayload.substring(0, 50000) + "... [TRUNCATED]"
                : responsePayload;

        ExternalApiLog mongoLog = new ExternalApiLog(
                null,
                sessionId,
                provider,
                endpoint,
                finalReqPayload,
                finalResPayload,
                statusCode,
                durationMs,
                Instant.now(),
                errorDetails);
        repository.save(mongoLog);
    }
}
