package com.slmplatform.logging;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class TraceabilityFilter extends OncePerRequestFilter {

    private final TraceLogRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TraceabilityFilter(TraceLogRepository repository) {
        this.repository = repository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") ||
                "OPTIONS".equalsIgnoreCase(request.getMethod()) ||
                path.endsWith("/seed-stream") ||
                path.endsWith("/execute");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, 1048576);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();
        String error = null;

        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } catch (Exception ex) {
            error = ex.getMessage();
            throw ex;
        } finally {
            long duration = System.currentTimeMillis() - startTime;

            String requestPayload = new String(requestWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
            String responsePayload = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);

            if (requestPayload.length() > 50000) {
                requestPayload = requestPayload.substring(0, 50000) + "... [TRUNCATED]";
            }
            if (responsePayload.length() > 50000) {
                responsePayload = responsePayload.substring(0, 50000) + "... [TRUNCATED]";
            }

            String sessionId = "unknown";
            try {
                if (!requestPayload.isBlank()) {
                    JsonNode node = objectMapper.readTree(requestPayload);
                    if (node.has("sessionId")) {
                        sessionId = node.get("sessionId").asText();
                    }
                }
            } catch (Exception e) {
                // Ignore parsing errors, keep 'unknown'
            }

            repository.save(new ApiTraceLog(
                    null,
                    sessionId,
                    request.getRequestURI(),
                    request.getMethod(),
                    requestPayload,
                    responsePayload,
                    response.getStatus(),
                    duration,
                    Instant.now(),
                    error));

            // CRITICAL: We must copy the cached response body back to the original response
            // stream, otherwise the client gets an empty body!
            responseWrapper.copyBodyToResponse();
        }
    }
}
