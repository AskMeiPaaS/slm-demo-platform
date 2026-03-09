package com.slmplatform.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slmplatform.tools.AgentTool;
import com.slmplatform.memory.MemoryService;
import com.slmplatform.ai.VoyageAiService;
import com.slmplatform.logging.ExternalApiLoggingService;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ToolOrchestrator {

    private final Map<String, AgentTool> tools;
    private final ObjectMapper objectMapper;
    private final RestClient ollamaClient;
    private final MemoryService memoryService;
    private final VoyageAiService voyageAiService;
    private final ExternalApiLoggingService externalApiLoggingService;
    private final String rerankerModel;
    private static final Logger log = LoggerFactory.getLogger(ToolOrchestrator.class);

    public ToolOrchestrator(
            List<AgentTool> availableTools,
            ObjectMapper objectMapper,
            MemoryService memoryService,
            VoyageAiService voyageAiService,
            ExternalApiLoggingService externalApiLoggingService,
            @Value("${platform.api.timeout-connect:300000}") long connectTimeout,
            @Value("${platform.api.timeout-read:300000}") long readTimeout,
            @Value("${voyageai.reranker-model:rerank-2-lite}") String rerankerModel,
            @Value("${ollama.host}") String ollamaHost) {

        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
        this.voyageAiService = voyageAiService;
        this.externalApiLoggingService = externalApiLoggingService;
        this.rerankerModel = rerankerModel;
        this.tools = availableTools.stream().collect(Collectors.toMap(AgentTool::getName, Function.identity()));

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(readTimeout));

        this.ollamaClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(ollamaHost)
                .build();
    }

    public String executeWithTools(String userPrompt, String modelName, String sessionId,
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {

        // Context Assembly
        memoryService.saveChatMessage(sessionId, "user", userPrompt);
        String chatHistory = memoryService.getFormattedChatHistory(sessionId);

        // FIX: Added the missing sessionId argument
        List<Double> promptEmbedding = voyageAiService.getEmbedding(userPrompt, sessionId);

        List<String> rawContextDocs = memoryService.retrieveVectorContext(promptEmbedding, "demo-agent-1")
                .stream().map(c -> c.content()).collect(Collectors.toList());

        List<String> topVectorContexts = voyageAiService.getTopRerankedDocuments(userPrompt, rawContextDocs,
                rerankerModel,
                sessionId);

        String vectorContextBlock;
        if (topVectorContexts.isEmpty()) {
            vectorContextBlock = "No relevant memory context found.";
        } else {
            vectorContextBlock = String.join("\n\n---\n\n", topVectorContexts);
            log.info("Reranker selected top {} memory context records successfully.", topVectorContexts.size());
        }

        // Prompt Assembly
        String toolDescriptions = tools.values().stream()
                .map(t -> "- " + t.getName() + ": " + t.getDescription()).collect(Collectors.joining("\n"));

        String systemPrompt = String.format(
                """
                        You are a movie recommendation assistant.

                        AVAILABLE TOOLS:
                        %s

                        RESPONSE RULES:
                        - You have access to exactly the tools listed above. DO NOT hallucinate or make up tools.
                        - To use a tool, output ONLY valid JSON matching this exact structure: {"tool": "tool_name", "parameters": {"query": "value"}}
                        - If you have the final answer, output ONLY plain text. DO NOT output JSON if you have the final answer.
                        - When recommending movies, ALWAYS try to summarize and suggest up to 5 movies based on the retrieved context. Format them clearly as a list.
                        - Do not explain your thought process.

                        Chat History: %s
                        Knowledge Base: %s
                        """,
                toolDescriptions, chatHistory, vectorContextBlock);

        List<Message> conversation = new java.util.ArrayList<>();
        conversation.add(new Message("system", systemPrompt));
        conversation.add(new Message("user", "User Request: " + userPrompt));

        for (int i = 0; i < 3; i++) {
            String slmResponse = callOllama(modelName, conversation, sessionId, emitter);

            try {
                // Clean formatting if LLM wrapped JSON in markdown code blocks
                String cleanJson = slmResponse.trim();
                if (cleanJson.startsWith("```json")) {
                    cleanJson = cleanJson.substring(7);
                } else if (cleanJson.startsWith("```")) {
                    cleanJson = cleanJson.substring(3);
                }
                if (cleanJson.endsWith("```")) {
                    cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
                }
                cleanJson = cleanJson.trim();

                JsonNode jsonNode = objectMapper.readTree(cleanJson);
                if (jsonNode.has("tool")) {
                    String toolName = jsonNode.get("tool").asText();
                    String params = jsonNode.has("parameters") ? jsonNode.get("parameters").toString() : "{}";

                    String toolResult = tools.containsKey(toolName) ? tools.get(toolName).execute(params, sessionId)
                            : "Tool not found.";

                    conversation.add(new Message("assistant", slmResponse));
                    conversation.add(new Message("user", "Tool Result: \n" + toolResult
                            + "\n\nCRITICAL: You have the tool results now. DO NOT call the tool again. Provide the final answer to the user in PLAIN TEXT."));
                    continue;
                } else {
                    // Valid JSON but not a tool call, assuming final answer
                    return handleFinalAnswer(sessionId, userPrompt, slmResponse);
                }
            } catch (Exception e) {
                // Not JSON, final answer
                return handleFinalAnswer(sessionId, userPrompt, slmResponse);
            }
        }
        return "Agent reached maximum tool iterations.";
    }

    private String handleFinalAnswer(String sessionId, String userPrompt, String slmResponse) {
        memoryService.saveChatMessage(sessionId, "assistant", slmResponse);
        String memoryContent = "User: " + userPrompt + "\\nAssistant: " + slmResponse;
        List<Double> embedding = voyageAiService.getEmbedding(memoryContent, sessionId);
        if (embedding != null && !embedding.isEmpty()) {
            memoryService.saveLongTermMemory(memoryContent, embedding, "demo-agent-1", sessionId);
        }
        return slmResponse;
    }

    private String callOllama(String model, List<Message> conversation, String sessionId,
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        StringBuilder rawResponseBuilder = new StringBuilder();
        StringBuilder contentBuilder = new StringBuilder();
        java.util.concurrent.atomic.AtomicBoolean suppressStream = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean checkedStream = new java.util.concurrent.atomic.AtomicBoolean(false);
        int[] statusCode = { 200 };
        String[] errorDetails = { null };

        ChatRequest req = new ChatRequest(model);
        req.messages.addAll(conversation);
        req.stream = true;

        String requestPayload = "Failed to parse Request";
        try {
            requestPayload = objectMapper.writeValueAsString(req);
        } catch (Exception e) {
        }

        final String finalRequestPayload = requestPayload;

        try {
            ollamaClient.post().uri("/v1/chat/completions").body(req)
                    .exchange((request, response) -> {
                        statusCode[0] = response.getStatusCode().value();
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(response.getBody()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank())
                                    continue;
                                rawResponseBuilder.append(line).append("\\n");
                                try {
                                    String jsonStr = line.startsWith("data: ") ? line.substring(6)
                                            : line.startsWith("data:") ? line.substring(5) : line;
                                    if (jsonStr.trim().equals("[DONE]"))
                                        continue;
                                    JsonNode node = objectMapper.readTree(jsonStr);
                                    String chunk = "";
                                    if (node.has("message") && node.get("message").has("content")) {
                                        chunk = node.get("message").get("content").asText();
                                    } else if (node.has("choices") && node.get("choices").isArray()
                                            && node.get("choices").size() > 0) {
                                        JsonNode delta = node.get("choices").get(0).get("delta");
                                        if (delta != null && delta.has("content")) {
                                            chunk = delta.get("content").asText();
                                        }
                                    }

                                    if (!chunk.isEmpty()) {
                                        contentBuilder.append(chunk);

                                        if (emitter != null) {
                                            if (!checkedStream.get()) {
                                                String currentContent = contentBuilder.toString().trim();
                                                if (currentContent.length() >= 5) { // Check prefix after a few chars
                                                    checkedStream.set(true);
                                                    if (currentContent.startsWith("{")
                                                            || currentContent.startsWith("```")) {
                                                        suppressStream.set(true);
                                                        emitter.send(
                                                                org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                                                        .event().name("status").data(Map.of("message",
                                                                                "Agent is using a tool...")));
                                                    } else {
                                                        emitter.send(
                                                                org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                                                        .event().name("chunk").data(Map.of("chunk",
                                                                                contentBuilder.toString()))); // flush
                                                                                                              // buffer
                                                    }
                                                }
                                            } else if (!suppressStream.get()) {
                                                emitter.send(
                                                        org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                                                .event().name("chunk").data(Map.of("chunk", chunk)));
                                            }
                                        }
                                    }
                                } catch (java.io.IOException e) {
                                    log.warn(
                                            "[ToolOrchestrator] Client disconnected unexpectedly. Halting LLM stream.");
                                    break; // Break the while loop
                                } catch (Exception e) {
                                    // Ignore JSON parse errors for incomplete chunks
                                }
                            }
                        }
                        return null;
                    });

            if (emitter != null && !checkedStream.get() && !suppressStream.get() && contentBuilder.length() > 0) {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("chunk")
                        .data(Map.of("chunk", contentBuilder.toString())));
            }
            return contentBuilder.toString();
        } catch (Exception e) {
            statusCode[0] = 500;
            errorDetails[0] = e.getMessage();
            return "Failed to execute SLM call: " + e.getMessage();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            externalApiLoggingService.logApiCall(
                    "Ollama", "/v1/chat/completions", finalRequestPayload, rawResponseBuilder.toString(), statusCode[0],
                    duration,
                    errorDetails[0], sessionId);
        }
    }
}

// Chat structure schemas based on standard OpenAI API used by Ollama mapped
// endpoints
@JsonIgnoreProperties(ignoreUnknown = true)
class ChatRequest {
    public String model;
    public double temperature;
    public boolean stream;
    public List<Message> messages = new java.util.ArrayList<>();

    public ChatRequest() {
    }

    public ChatRequest(String model) {
        this.model = model;
        this.temperature = 0.0;
        this.stream = false;
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Message {
    public String role;
    public String content;

    public Message() {
    }

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class ChatResponse {
    public List<Choice> choices;
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Choice {
    public Message message;
}