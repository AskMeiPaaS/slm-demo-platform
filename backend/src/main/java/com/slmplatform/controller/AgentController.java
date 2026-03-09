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

    @PostMapping("/agent/execute")
    public AgentResponse executeTask(@RequestBody AgentRequest request) {
        long startTime = System.currentTimeMillis();
        String sessionId = request.sessionId() != null ? request.sessionId() : "default-session";
        // Force llama3.2:1b to prevent cached Qwen frontend payloads from hanging the
        // CPU
        String finalAnswer = toolOrchestrator.executeWithTools(request.prompt(), "llama3.2:1b", sessionId);
        long durationMs = System.currentTimeMillis() - startTime;
        return new AgentResponse(finalAnswer, durationMs);
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