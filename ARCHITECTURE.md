# SLM Demo Platform - Architecture Document

This document details the architecture, design decisions, and data flow of the Small Language Model (SLM) Demo Platform. The platform demonstrates how to build a privacy-first, locally-hosted AI agent augmented with cloud-based vector search and robust observability.

## 🏗️ High-Level Architecture

The system is containerized using Docker Compose and consists of four primary nodes:

1. **Frontend**: Next.js (React) application serving the UI.
2. **Backend**: Spring Boot 3 Java application orchestrating the AI logic.
3. **Database**: MongoDB serving as both a document store and a Vector Database.
4. **Local LLM Engine**: Ollama running the lightweight `llama3.2:1b` model.

---

## 💻 1. Frontend Layer (Next.js)

The frontend is a React-based single-page application built with Next.js App Router and Tailwind CSS. It communicates exclusively with the Spring Boot Backend.

### Key Capabilities
- **Dynamic Configuration**: The UI dynamically polls `/api/v1/data/status` at runtime to determine if the `ENABLE_DATA_LOADER` environment feature flag is active. This avoids stale Next.js build-time baked variables.
- **Persistent UI State**: The client generates a local UUID device identifier and syncs the active tab, chat session ID, and observability session ID to the backend via `/api/v1/uistate/{deviceId}`. This allows users to preserve their exact UI state across browser refreshes.
- **Server-Sent Events (SSE)**: The Data Loader UI subscribes to an SSE stream (`/api/v1/data/seed-stream`) to render real-time progression while the backend parses local JSON files and generates vector embeddings.
- **Agent Console**: An interactive chat interface that leverages an ongoing `chatSessionId` to execute conversational prompts via the backend's `/api/v1/agent/execute` endpoint.
- **Observability Dashboard**: Visualizes end-to-end telemetry traces matching the `observabilitySessionId` by polling the backend `/v1/logs` and `/v1/traces/{id}` APIs.

---

## ⚙️ 2. Backend Orchestration (Spring Boot 3)

The backend is built with Spring Boot using Java 21 Virtual Threads (`spring.threads.virtual.enabled=true`) for high-concurrency, non-blocking asynchronous I/O performance.

### 2.1 Agent Orchestration & Chat API
The core entry point for the SLM is `ToolOrchestrator.java`. When a prompt arrives:
1. **Memory Retrieval**: The backend fetches previous conversation context associated with the user's `chatSessionId`.
2. **OpenAI Schema Format**: The backend formats the prompt into the industry-standard `ChatRequest/Message` JSON structure (e.g., `messages: [{role: "system", content: "..."}, {role: "user", content: "..."}]`).
3. **Local Inference**: The payload is dispatched to the locally hosted Ollama container via its HTTP `/v1/chat/completions` REST endpoint.
4. **Tool Calling**: Llama 3.2 evaluates available system tools (defined in the system prompt) and can output JSON requesting external data lookups, triggering backend service intercepts like the `MovieSearchTool`.

### 2.2 Voyage AI Vector Search & Reranking Strategy
To provide high-quality knowledge retrieval to the SLM without overwhelming its context window, a sophisticated hybrid RAG (Retrieval-Augmented Generation) pipeline is applied:
1. **Embedding**: The `DataSeederService` embeds the comprehensive `fullplot` field of the MongoDB movies dataset using the Voyage AI `/embeddings` API (`voyage-4-lite`), storing the resulting 1024-dimensional vectors in MongoDB.
2. **Search**: The `MovieSearchTool` converts user queries into vectors and executes a MongoDB Atlas `$vectorSearch` pipeline to retrieve candidate matches based on their `fullplot` semantic similarity.
3. **Reranking**: Candidate `fullplot` texts are evaluated via the Voyage AI `/rerank` API. Documents below the configurable `relevanceThreshold` (e.g., 0.35) are discarded.
4. **Context Isolation**: The backend retrieves the Top-K indices (`VOYAGE_RERANKER_TOP_K`) of the surviving documents. However, to minimize LLM hallucination and repetition, the backend **isolates and injects only the shorter `title` and `plot` properties** into the strict Llama context payload instead of the massive `fullplot`.

### 2.3 Comprehensive Observability & Telemetry
The backend employs a multi-layered interceptor approach to track AI behavior natively:
- **`TraceabilityFilter`**: A standard Spring Web Filter that intercepts all incoming frontend HTTP requests. It captures deep telemetry (Payloads, latency, status codes, endpoints) and logs them into MongoDB `api_trace_logs`.
- **`ExternalApiLoggingService`**: Monitors outbound RestClient API requests targeting third-party services like Ollama and Voyage AI. Payloads, latencies, and failures are recorded into the `external_api_logs` collection.
- **Trace Correlation**: Since `chatSessionId` flows strictly from the UI through to the outbound external APIs, developers can enter any Session UUID into the Next.js Observability Dashboard and visually rebuild a sequential diagram of every node interaction and JSON payload involved in answering that specific question.

---

## 🗄️ 3. Persistence Layer (MongoDB)

MongoDB acts as a flexible document store and a high-performance vector database.
- **Collections:**
  - `movies`: Stores raw demo data alongside the embedded Voyage AI vectors (`List<Double>`).
  - `ui_states`: Temporarily caches user-specific browser UI toggle states.
  - `api_trace_logs` & `external_api_logs`: Audit stores powering the Observability Dashboard telemetry UI.
- **Vector IndexBuilder**: During the Data Loader phase, the Spring backend dynamically establishes the MongoDB `$vectorSearch` index, configuring the `cosine` similarity algorithm against the embedding field without manual atlas configuration.

---

## 🧠 4. Local SLM (Ollama & Llama 3.2)

To ensure privacy, offline-accessibility, and fast JSON structuring, the LLM reasoning is handled locally using `ollama/ollama:latest` Docker image.
- **Model Choice**: `llama3.2:1b` represents a highly capable small parameter model capable of strict instruction following despite consuming low residential hardware RAM constraints.
- **Security Check**: By isolating the Ollama container within the Docker network, and executing commands via standard API, the system avoids vendor lock-in and prevents proprietary conversational data from leaking to cloud generation networks like OpenAI or Anthropic. Cloud services are strictly reserved for secure, stateless mathematically opaque Voyage AI Vector queries.

---

## 🔄 5. End-to-End Query Flow Analysis

Based on the codebase analysis, here is the complete flow of a document/query from the user's input in the chat UI, to the payload sent to Ollama, and how the response is captured.

### 5.1 Frontend: User Query Initiation
- **Component**: `ChatInterface` in `frontend/app/page.tsx`
- **Action**: The user types a message and clicks send. The message is optimistically appended to the chat UI array as a `user` message.
- **Request**: An HTTP POST request is made to the backend endpoint `/api/v1/agent/execute` with the JSON payload:
  ```json
  {
    "prompt": "User's query",
    "agentId": "demo-agent-1",
    "modelName": "qwen3.5:0.8b",
    "sessionId": "current-chat-session-uuid"
  }
  ```

### 5.2 Backend: Agent Controller & Orchestrator
- **Entry Point**: `AgentController.executeTask()` receives the payload and forwards the `prompt`, `modelName`, and `sessionId` to `ToolOrchestrator.executeWithTools()`.
- **Memory & Logging**:
  - The `MemoryService` saves the raw chat message (role "user").
  - `VoyageAiService` executes a vector embedding search for relevant context using the user's prompt.
  - The `MemoryService` performs a retrieval step (`retrieveVectorContext`), followed by re-ranking via `VoyageAiService` to extract the `topVectorContexts`.

### 5.3 Payload Construction
Inside `ToolOrchestrator.executeWithTools()`, the payload for Ollama is constructed:
- **System Prompt**: A highly detailed instruction set (`systemPrompt`) is created. It concatenates:
  - Base instructions (`"You are a movie recommendation assistant."`)
  - Tool descriptions (e.g. `search: ...`)
  - A stringified version of the chat history.
  - The vector context block (RAG knowledge base).
- **User Prompt (`currentContext`)**: Initially set to `"User Request: " + userPrompt`.

### 5.4 Interaction with Ollama (The Execution Loop)
The application runs a `for(int i = 0; i < 3; i++)` loop to allow the SLM to call tools up to 3 times before returning a final answer.
- **Payload Sent to Ollama**:
  `callOllama()` constructs an OpenAI-compatible JSON payload that is sent to the `/v1/chat/completions` REST endpoint of the Ollama container. The payload structure is:
  ```json
  {
    "model": "qwen3.5:0.8b",
    "temperature": 0.0,
    "stream": false,
    "messages": [
      { "role": "system", "content": "..." },
      { "role": "user", "content": "User Request: ..." }
    ]
  }
  ```
- **Tool Call Handling & Loop Update**:
  When Ollama responds, the application cleans up any markdown blocks and parses the response as JSON.
  - If a `"tool"` JSON block is detected, it executes the tool mapped in the backend.
  - The tool's output is *appended* to the `currentContext` variable (e.g. `\n\nAssistant attempted to use: search\nTool Result: ...\n\nProvide the final answer based on this result.`).
  - The loop repeats, passing the updated `currentContext` as the user message back to Ollama.

### 5.5 Capturing the Response
Once Ollama provides a final answer (either out of tools, or standard text that fails JSON parsing), it breaks from the loop via `handleFinalAnswer()`.
- **Response Capture**:
  - The final plain-text string is logged to `MemoryService` under the `"assistant"` role.
  - A long-term memory embedding combination (`User: ... \n Assistant: ...`) is generated and vectorized.
- **Trace Logging**:
  - In the `finally` block of `callOllama()`, every single step—the raw `requestPayload` sent to Ollama, the `rawResponse` captured, elapsed time, and HTTP status code—is saved directly to the database via `ExternalApiLoggingService`.
- **Client Return**:
  - The final text string is wrapped in an `AgentResponse` and sent as a 200 OK back to the React UI, which displays it inside a green agent chat bubble.
