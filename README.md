# Full-Stack AI Agent Platform (SLM Demo)

This project is a comprehensive end-to-end AI platform demonstrating how to build a locally-hosted Small Language Model (SLM) Agent that leverages external Vector Search, Reranking, and robust Observability.

## 🏗️ Architecture Overview

The system consists of four primary components, orchestrated effortlessly through Docker Compose:

1. **Frontend**: A Next.js (React/Tailwind) single-page application that provides a modern User Interface containing a Data Loader, an Agent Console, and an Observability Dashboard.
2. **Backend**: A Spring Boot (Java 21) REST API responsible for agent orchestration, tool execution, memory management, and database interactions.
3. **Database**: A locally hosted MongoDB instance serving as a Vector Database (for embeddings) and a standard document store (for logging and structured data).
4. **Local LLM Engine**: An Ollama instance hosting the `llama3.1:8b` Small Language Model. This drives the core reasoning and tool-calling capabilities of the agent without sending sensitive prompts to external cloud providers.
1.  **Frontend**: A Next.js (React/Tailwind) single-page application that provides a modern User Interface containing a Data Loader, an Agent Console, and an Observability Dashboard.
2.  **Backend**: A Spring Boot (Java 21) REST API responsible for agent orchestration, tool execution, memory management, and database interactions.
3.  **Database**: A locally hosted MongoDB instance serving as a Vector Database (for embeddings) and a standard document store (for logging and structured data).
4.  **Local LLM Engine**: An Ollama instance hosting the `llama3.1:8b` Small Language Model. This drives the core reasoning and tool-calling capabilities of the agent without sending sensitive prompts to external cloud providers.

## 🚀 Invocation Flow

When a user submits a prompt via the Agent Console (`/api/v1/agent/execute`):

1.  **Context Assembly**: The Spring Boot `ToolOrchestrator` retrieves user chat history and fetches relevant semantic memory by creating an embedding of the prompt (via Voyage AI) and querying the MongoDB vector store.
2.  **SLM Reasoning**: The `ToolOrchestrator` builds a rich system prompt using the industry standard OpenAI `ChatRequest/Message` format, containing available tool descriptions, history, and context. It then dispatches it to the locally hosted **Ollama (Llama 3.1)** `/v1/chat/completions` endpoint.
3.  **Tool Execution**: If Llama 3.1 determines it needs external data (e.g., searching for a movie), it outputs a structured JSON tool request. The backend intercepts this, executes the `MovieSearchTool`, and feeds the result back to Llama 3.1.
4.  **Vector Search & Reranking**:
    -   `MovieSearchTool` creates an embedding for the search query using **Voyage AI**.
    -   It performs an `$vectorSearch` against the MongoDB `movies` collection to extract the large `fullplot` content.
    -   It sends the candidate documents back to **Voyage AI**'s `/rerank` API to map the top matches to a set of specific indices based on a dynamically configurable `VOYAGE_RERANKER_TOP_K` value.
    -   Using the highly-relevant indices, the `MovieSearchTool` isolates only the `title` and short `plot` to supply to the Llama 3.1 LLM, significantly minimizing hallucinations and avoiding repetitive output.
5.  **Final Response**: Llama 3.1 synthesizes the highly-relevant movie context into a final, natural-language answer sent back to the Next.js frontend.

## 🧠 AI Integrations

### 1. Locally Hosted Llama 3.1 on Ollama
- **Purpose**: Acts as the "Brain" of the system. It receives prompts, decides when to use tools, interprets tool outputs, and generates the final response. It runs entirely on the local machine via Docker, ensuring data privacy and reducing latency for cognitive tasks.

### 2. Voyage AI (Cloud)
Voyage AI provides state-of-the-art embedding and reranking models retrieved via API.
- **Embeddings API**: Used to convert movie properties and user prompts into high-dimensional vector arrays (`List<Double>`) to enable semantic search in MongoDB.
- **Rerank API**: Used post-search to re-evaluate the candidate documents returned by MongoDB. This drastically improves the quality of the context provided to Llama 3.1 by enforcing strict relevance thresholds.

## 📊 Logging & Observability

Observability is built into the core of the backend system to monitor performance, debug errors, and track LLM usage:

- **Incoming API Traces**: The `TraceabilityFilter` captures every HTTP request hitting the Spring Boot backend, reliably caching and recording the full `requestPayload`, `responsePayload`, endpoint, method, status code, and latency in the `api_trace_logs` MongoDB collection. The Next.js frontend polls this data to drive the Observability Dashboard.
- **External API Logs**: A dedicated `ExternalApiLoggingService` intercepts outgoing calls made to Voyage AI (`/embeddings` and `/rerank`) and Ollama (`/api/generate`). 
  - It asynchronously records the complete request payloads, response bodies, execution latency, and error details into the `external_api_logs` MongoDB collection.
  - This allows deep inspection of exactly what context was sent to Llama 3.1, how Llama 3.1 replied, and how long Voyage AI took to generate vectors.
- **Chat Traceability**: Every time the Next.js Agent Console is opened, a unique `Session UUID` is generated. This ID flows seamlessly from the Frontend through the `AgentController`, all the way to the Vector Embedding process. This links the local memory, prompts, and `external_api_logs` directly to the specific user chat session!
- **API Timeouts**: All external API calls (to Voyage AI and Ollama) are configured with customizable `read` and `connect` timeouts (defaulting to 5 minutes) to prevent the agent thread from hanging indefinitely.

### 🗄️ MongoDB Collections

The platform uses a single MongoDB database named `agentplatform` which contains the following key collections:

- **`movies`**: Stores the core sample data (movie titles, properties, full plots) alongside their high-dimensional vector embeddings generated by Voyage AI. This collection is the primary target for `$vectorSearch`.
- **`api_trace_logs`**: Stores the structured observability traces for incoming HTTP requests (Frontend to Backend). Used to visualize latency and traffic.
- **`external_api_logs`**: Stores the detailed request/response payloads and performance metrics for all outbound LLM and embedding API calls (Backend to Voyage AI / Ollama).

## 🛠️ Getting Started

Ensure you have Docker and Docker Compose installed. You will also need a Voyage AI API Key.

1. Ensure the following parameters are set in your `.env` file before starting:
   ```env
   VOYAGE_AI_API_KEY=your-voyage-api-key-here  # Or leave as "default" if you just want to test without RAG
   VOYAGE_MODEL=voyage-4-lite  # Or "voyage-3-lite" for a cheaper option, but with less capabilities
   VOYAGE_EMBEDDING_DIMENSION=1024
   VOYAGE_RERANKER_MODEL=rerank-2-lite  # Or "rerank-1-lite" for a cheaper option, but with less capabilities
   VOYAGE_RERANKER_TOP_K=3  # Configure how many movie recommendations the Reranker will select for the Agent Prompt
   ENABLE_DATA_LOADER=true  # Controls whether the Next.js Data Loader UI Button is accessible
   PLATFORM_API_MASTER_KEY=your-secure-master-key-here
   API_TIMEOUT_CONNECT=300000 # 5 minutes in milliseconds
   API_TIMEOUT_READ=300000 # 5 minutes in milliseconds
   ```
2. Run `docker-compose up --build -d`.
3. Give Ollama a moment to pull the `llama3.1:8b` model automatically.
4. Access the UI at `http://localhost:3000`.
5. Navigate to the **Data Loader** tab to seed the MongoDB database and generate initial vector embeddings.
6. **Optional Data Loader Toggle**: If you have already loaded your data and no longer need the Data Loader UI, you can set `ENABLE_DATA_LOADER=false` in the `.env` to gracefully gray-out and disable the UI feature.
7. Switch to the **Agent Console** to start chatting with your locally-hosted Movie Expert agent!
