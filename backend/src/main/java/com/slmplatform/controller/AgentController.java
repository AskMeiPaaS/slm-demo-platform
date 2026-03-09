package com.slmplatform.controller;

import org.jspecify.annotations.NullMarked;
import org.springframework.web.bind.annotation.*;
import com.slmplatform.agent.ToolOrchestrator;
import com.slmplatform.logging.TraceLogRepository;
import com.slmplatform.logging.ApiTraceLog;
import com.slmplatform.logging.ExternalApiLogRepository;
import com.slmplatform.logging.ExternalApiLog;
import com.slmplatform.memory.MemoryService;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.time.Instant;

@NullMarked
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class AgentController {

    private final ToolOrchestrator toolOrchestrator;
    private final TraceLogRepository logRepository;
    private final ExternalApiLogRepository externalLogRepository;
    private final MemoryService memoryService;

    public AgentController(ToolOrchestrator toolOrchestrator, TraceLogRepository logRepository,
            ExternalApiLogRepository externalLogRepository, MemoryService memoryService) {
        this.toolOrchestrator = toolOrchestrator;
        this.logRepository = logRepository;
        this.externalLogRepository = externalLogRepository;
        this.memoryService = memoryService;
    }

    @PostMapping(value = "/agent/execute", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter executeTask(
            @RequestBody AgentRequest request) {
        long startTime = System.currentTimeMillis();
        String sessionId = request.sessionId() != null ? request.sessionId() : "default-session";
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(
                300000L); // 5-minute timeout

        emitter.onCompletion(() -> System.out.println("[AgentController] SSE Stream completed natively."));
        emitter.onTimeout(() -> {
            System.err.println("[AgentController] SSE Stream timed out. Completing manually.");
            emitter.complete();
        });
        emitter.onError(e -> {
            System.err.println("[AgentController] SSE Stream encountered an error. Completing manually.");
            emitter.completeWithError(e);
        });

        Thread.startVirtualThread(() -> {
            try {
                // Force llama3.1:8b and pass the SseEmitter down
                toolOrchestrator.executeWithTools(request.prompt(), "llama3.1:8b", sessionId, emitter);
                long durationMs = System.currentTimeMillis() - startTime;
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("done")
                        .data(Map.of("timeTakenMs", durationMs)));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @DeleteMapping("/agent/memory/{sessionId}")
    public Map<String, String> clearMemory(@PathVariable String sessionId) {
        memoryService.clearSessionMemory(sessionId);
        return Map.of("status", "Memory cleared");
    }

    @GetMapping("/agent/memory/{sessionId}")
    public List<com.slmplatform.memory.ChatMemory> getMemory(@PathVariable String sessionId) {
        return memoryService.getRawChatHistory(sessionId);
    }

    @GetMapping("/logs")
    public List<ApiTraceLog> getLogs() {
        return logRepository.findAll();
    }

    @GetMapping("/traces/{sessionId}")
    public List<UnifiedTraceLog> getSessionTraces(@PathVariable String sessionId) {
        List<ApiTraceLog> apiLogs = logRepository.findBySessionId(sessionId);
        List<ExternalApiLog> externalLogs = externalLogRepository.findBySessionId(sessionId);

        List<UnifiedTraceLog> unifiedLogs = new ArrayList<>();

        for (ApiTraceLog log : apiLogs) {
            unifiedLogs.add(new UnifiedTraceLog(
                    "Incoming", "Frontend", log.endpoint(),
                    log.requestPayload(), log.responsePayload(),
                    log.statusCode(), log.durationMs(), log.timestamp(), log.errorDetails()));
        }

        for (ExternalApiLog log : externalLogs) {
            unifiedLogs.add(new UnifiedTraceLog(
                    "Outgoing", log.provider(), log.endpoint(),
                    log.requestPayload(), log.responsePayload(),
                    log.statusCode(), log.durationMs(), log.timestamp(), log.errorDetails()));
        }

        unifiedLogs.sort(Comparator.comparing(UnifiedTraceLog::timestamp));
        return unifiedLogs;
    }
}

record AgentRequest(String prompt, String agentId, String modelName, String sessionId) {
}

record AgentResponse(String result, long timeTakenMs) {
}

record UnifiedTraceLog(String type, String provider, String endpoint, String requestPayload, String responsePayload,
        int statusCode, long durationMs, Instant timestamp, String errorDetails) {
}