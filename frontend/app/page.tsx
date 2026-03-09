'use client';

import React, { useState, useEffect } from 'react';
import { Terminal, Activity, Send, Server, Database, BrainCircuit, HardDriveDownload, CheckCircle2, ChevronDown, ChevronRight, Search } from 'lucide-react';

export default function App() {
  const [isDataLoaderEnabled, setIsDataLoaderEnabled] = useState(true); // Default to true until fetched
  const [activeTab, setActiveTab] = useState<'data' | 'chat' | 'dashboard'>('chat'); // Default to chat until fetched
  const [chatSessionId, setChatSessionId] = useState<string>('');
  const [observabilitySessionId, setObservabilitySessionId] = useState<string>('');
  const [deviceId, setDeviceId] = useState<string>('');
  const [isStateLoaded, setIsStateLoaded] = useState(false);

  useEffect(() => {
    // Generate or retrieve persistent device ID
    let currentId = localStorage.getItem('slm_device_id');
    if (!currentId) {
      currentId = crypto.randomUUID();
      localStorage.setItem('slm_device_id', currentId);
    }
    setDeviceId(currentId);

    // Fetch persisted UI State and dynamic config from backend
    Promise.all([
      fetch(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api'}/v1/uistate/${currentId}`)
        .then(res => res.ok ? res.json() : {}).catch(() => ({})),
      fetch(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api'}/v1/data/status`)
        .then(res => res.ok ? res.json() : { enabled: false }).catch(() => ({ enabled: false }))
    ]).then(([uiStateData, configData]: [any, any]) => {

      const dataEnabled = configData.enabled === true;
      setIsDataLoaderEnabled(dataEnabled);

      if (uiStateData.activeTab) {
        if (uiStateData.activeTab === 'data' && !dataEnabled) {
          setActiveTab('chat');
        } else {
          setActiveTab(uiStateData.activeTab);
        }
      } else {
        setActiveTab(dataEnabled ? 'data' : 'chat');
      }

      if (uiStateData.chatSessionId) setChatSessionId(uiStateData.chatSessionId);
      else setChatSessionId(crypto.randomUUID());

      if (uiStateData.observabilitySessionId) setObservabilitySessionId(uiStateData.observabilitySessionId);

      setIsStateLoaded(true);
    });
  }, []);

  // Sync state changes to backend
  useEffect(() => {
    if (!isStateLoaded || !deviceId) return;

    // Slight debounce for saves
    const timer = setTimeout(() => {
      fetch(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api'}/v1/uistate/${deviceId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ activeTab, chatSessionId, observabilitySessionId })
      }).catch(e => console.error("Failed to sync UI state", e));
    }, 500);
    return () => clearTimeout(timer);
  }, [activeTab, chatSessionId, observabilitySessionId, deviceId, isStateLoaded]);

  const clearUiState = async () => {
    if (deviceId) {
      await fetch(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api'}/v1/uistate/${deviceId}`, {
        method: 'DELETE'
      });
    }
    localStorage.removeItem('slm_device_id');
    window.location.reload();
  };

  if (!isStateLoaded) {
    return <div className="min-h-screen bg-gray-950 flex items-center justify-center text-emerald-500 animate-pulse"><Server size={32} /></div>;
  }

  return (
    <div className="min-h-screen bg-gray-950 text-gray-200 font-sans flex flex-col">
      <nav className="border-b border-gray-800 bg-gray-900/50 p-4 sticky top-0 z-50 backdrop-blur">
        <div className="max-w-6xl mx-auto flex justify-between items-center">
          <div className="flex items-center gap-2">
            <BrainCircuit className="text-emerald-500" />
            <h1 className="text-xl font-bold tracking-tight">SLM Platform <span className="text-xs bg-emerald-500/20 text-emerald-400 px-2 py-1 rounded">Llama 3.2:1b</span></h1>
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => isDataLoaderEnabled && setActiveTab('data')}
              disabled={!isDataLoaderEnabled}
              className={`flex items-center gap-2 px-4 py-2 rounded transition ${!isDataLoaderEnabled ? 'opacity-50 cursor-not-allowed text-gray-500' : activeTab === 'data' ? 'bg-purple-600/20 text-purple-400' : 'hover:bg-gray-800 text-gray-400'}`}
              title={!isDataLoaderEnabled ? "Data loading is disabled via environment configuration." : ""}
            >
              <HardDriveDownload size={18} /> Data Loader
            </button>
            <button
              onClick={() => setActiveTab('chat')}
              className={`flex items-center gap-2 px-4 py-2 rounded transition ${activeTab === 'chat' ? 'bg-emerald-600/20 text-emerald-400' : 'hover:bg-gray-800 text-gray-400'}`}
            >
              <Terminal size={18} /> Agent Console
            </button>
            <button
              onClick={() => setActiveTab('dashboard')}
              className={`flex items-center gap-2 px-4 py-2 rounded transition ${activeTab === 'dashboard' ? 'bg-blue-600/20 text-blue-400' : 'hover:bg-gray-800 text-gray-400'}`}
            >
              <Activity size={18} /> Observability
            </button>
            <button
              onClick={clearUiState}
              className="flex items-center gap-2 px-3 py-2 rounded text-xs ml-4 border border-red-900/50 text-red-500 hover:bg-red-500/10 transition"
              title="Clear persistent UI state and start fresh"
            >
              Reset Session
            </button>
          </div>
        </div>
      </nav>

      <main className="flex-1 max-w-6xl w-full mx-auto p-6">
        <div className={activeTab === 'data' ? 'block' : 'hidden'}>
          <DataManagementTab />
        </div>
        <div className={activeTab === 'chat' ? 'block' : 'hidden'}>
          <ChatInterface chatSessionId={chatSessionId} />
        </div>
        <div className={activeTab === 'dashboard' ? 'block' : 'hidden'}>
          <ObservabilityDashboard searchSessionId={observabilitySessionId} setSearchSessionId={setObservabilitySessionId} />
        </div>
      </main>
    </div>
  );
}

// --- Data Management Tab ---
function DataManagementTab() {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [progressMsg, setProgressMsg] = useState("");
  const eventSourceRef = React.useRef<EventSource | null>(null);

  useEffect(() => {
    // Cleanup on unmount to prevent dangling connections
    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }
    };
  }, []);

  const processData = () => {
    setLoading(true);
    setResult(null);
    setProgressMsg("Connecting to server...");

    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }

    const eventSource = new EventSource(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api'}/v1/data/seed-stream`);
    eventSourceRef.current = eventSource;

    eventSource.onmessage = (event) => {
      const data = JSON.parse(event.data);
      if (data.status === 'progress') {
        setProgressMsg(data.message);
      } else if (data.status === 'success' || data.status === 'error') {
        setResult(data);
        setLoading(false);
        setProgressMsg("");
        eventSource.close();
      }
    };

    eventSource.onerror = (error) => {
      console.warn("SSE stream encountered an error or reconnect event.", error);
      // We now have keep-alives implemented on the backend. 
      // If we see an error, strictly close the connection to prevent infinite auto-reconnects
      if (eventSource.readyState !== EventSource.CLOSED) {
        setResult({ status: 'error', message: 'Connection to server lost or failed.' });
        setLoading(false);
        setProgressMsg("");
        eventSource.close();
      }
    };
  };

  return (
    <div className="animate-in fade-in duration-500 max-w-3xl mx-auto space-y-6">
      <div className="bg-gray-900 border border-gray-800 p-8 rounded-xl shadow-lg text-center space-y-6">
        <div className="w-16 h-16 bg-purple-500/10 rounded-full flex items-center justify-center mx-auto border border-purple-500/20">
          <HardDriveDownload size={32} className="text-purple-400" />
        </div>
        <div>
          <h2 className="text-2xl font-bold text-white mb-2">Sample Data Pipeline</h2>
          <p className="text-gray-400">
            This pipeline will drop the database to prevent duplicates, parse your local `movies.json` file, connect to Voyage AI to generate vector embeddings, and load them into the <span className="font-mono text-purple-300 bg-purple-900/30 px-1 rounded">movies</span> MongoDB collection. Finally, it builds a Vector Search Index named <span className="font-mono text-emerald-300 bg-emerald-900/30 px-1 rounded">vector_index</span> on the collection.
          </p>
        </div>

        <button
          onClick={processData}
          disabled={loading}
          className="bg-purple-600 hover:bg-purple-500 text-white px-6 py-3 rounded-lg font-medium transition-all shadow-lg disabled:opacity-50 flex items-center gap-2 mx-auto"
        >
          {loading ? <Server className="animate-spin" size={18} /> : <Database size={18} />}
          {loading ? 'Processing...' : 'Load & Embed Sample Data'}
        </button>

        {loading && progressMsg && (
          <div className="p-4 rounded-lg text-left mt-6 bg-purple-500/10 border border-purple-500/20 text-purple-300 animate-pulse">
            <h3 className="font-bold flex items-center gap-2 mb-2">
              <Activity size={18} /> Loading Progress
            </h3>
            <p className="text-sm font-mono">{progressMsg}</p>
          </div>
        )}

        {result && (
          <div className={`p-4 rounded-lg text-left mt-6 ${result.status === 'success' ? 'bg-emerald-500/10 border border-emerald-500/20 text-emerald-300' : 'bg-red-500/10 border border-red-500/20 text-red-300'}`}>
            <h3 className="font-bold flex items-center gap-2 mb-2">
              {result.status === 'success' ? <><CheckCircle2 size={18} /> Processing Complete</> : 'Error processing data'}
            </h3>
            {result.status === 'success' ? (
              <ul className="list-disc pl-5 text-sm space-y-1 text-emerald-400/80">
                <li>Movies processed & embedded: <strong>{result.movies_embedded}</strong></li>
              </ul>
            ) : (
              <p className="text-sm">{result.message}</p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

// --- Chat Interface Component ---
function ChatInterface({ chatSessionId }: { chatSessionId: string }) {
  const [prompt, setPrompt] = useState('');
  const [messages, setMessages] = useState<{ role: string, content: string, timeTakenMs?: number }[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!chatSessionId) return;
    fetch(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api'}/v1/agent/memory/${chatSessionId}`)
      .then(res => res.json())
      .then(data => {
        if (Array.isArray(data)) {
          setMessages(data.map((msg: any) => ({ role: msg.role.toLowerCase(), content: msg.content })));
        }
      })
      .catch(e => console.error("Failed to load chat history", e));
  }, [chatSessionId]);

  const executeAgent = async () => {
    if (!prompt.trim()) return;
    const userMsg = prompt;
    setMessages(prev => [...prev, { role: 'user', content: userMsg }]);
    setPrompt('');
    setLoading(true);

    try {
      const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api'}/v1/agent/execute`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prompt: userMsg,
          agentId: 'demo-agent-1',
          modelName: 'llama3.2:1b',
          sessionId: chatSessionId
        })
      });
      const data = await res.json();
      setMessages(prev => [...prev, { role: 'agent', content: data.result || 'No response.', timeTakenMs: data.timeTakenMs }]);
    } catch (error) {
      setMessages(prev => [...prev, { role: 'system', content: "Backend unreachable." }]);
    }
    setLoading(false);
  };

  return (
    <div className="flex flex-col h-[80vh] bg-gray-900 rounded-xl border border-gray-800 overflow-hidden shadow-2xl animate-in fade-in duration-500">
      <div className="flex-1 overflow-y-auto p-6 space-y-6">
        {messages.length === 0 && (
          <div className="text-center text-gray-500 mt-20">
            <BrainCircuit size={48} className="mx-auto mb-4 opacity-50 text-emerald-500" />
            <p>Llama 3.2 Agent is ready. Ask a question about the movies loaded in your database!</p>
            <p className="text-xs mt-2 opacity-50 font-mono">Session UUID: {chatSessionId}</p>
          </div>
        )}
        {messages.map((msg, i) => (
          <div key={i} className={`flex gap-4 ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div className={`max-w-[80%] p-4 rounded-lg ${msg.role === 'user' ? 'bg-emerald-600/20 border border-emerald-500/30 text-emerald-100' :
              msg.role === 'system' ? 'bg-red-500/20 border border-red-500/30 text-red-200' :
                'bg-gray-800 border border-gray-700 text-gray-200'
              }`}>
              <div className="text-xs opacity-50 mb-1 uppercase tracking-wider font-bold">
                {msg.role}
                {msg.timeTakenMs && <span className="ml-2 lowercase font-normal hidden sm:inline-block">({(msg.timeTakenMs / 1000).toFixed(2)}s)</span>}
              </div>
              <div className="whitespace-pre-wrap">{msg.content}</div>
            </div>
          </div>
        ))}
        {loading && (
          <div className="flex gap-2 items-center text-emerald-500 animate-pulse">
            <Server size={16} /> Agent is reasoning...
          </div>
        )}
      </div>
      <div className="p-4 bg-gray-900 border-t border-gray-800">
        <div className="flex gap-2 relative">
          <input
            type="text"
            className="w-full bg-gray-950 border border-gray-700 rounded-lg pl-4 pr-12 py-3 focus:outline-none focus:border-emerald-500 focus:ring-1 focus:ring-emerald-500 transition"
            placeholder="Type your message..."
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && executeAgent()}
            disabled={loading}
          />
          <button
            onClick={executeAgent}
            disabled={loading || !prompt.trim()}
            className="absolute right-2 top-2 bottom-2 bg-emerald-600 hover:bg-emerald-500 text-white rounded p-2 transition disabled:opacity-50"
          >
            <Send size={18} />
          </button>
        </div>
      </div>
    </div>
  );
}

// --- Observability Dashboard Component ---
function ObservabilityDashboard({ searchSessionId, setSearchSessionId }: { searchSessionId: string, setSearchSessionId: (id: string) => void }) {
  const [logs, setLogs] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [sessionTraces, setSessionTraces] = useState<any[] | null>(null);
  const [expandedTraceId, setExpandedTraceId] = useState<number | null>(null);

  const fetchLogs = async () => {
    try {
      const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api'}/v1/logs`);
      const data = await res.json();
      setLogs(data.reverse());
    } catch (error) { }
    setLoading(false);
  };

  const searchSession = async () => {
    if (!searchSessionId.trim()) return;
    setLoading(true);
    try {
      const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api'}/v1/traces/${searchSessionId}`);
      if (res.ok) {
        const data = await res.json();
        setSessionTraces(data);
      } else {
        setSessionTraces([]);
      }
    } catch (error) {
      setSessionTraces([]);
    }
    setLoading(false);
  };

  // Auto-search if a session ID was restored from state
  useEffect(() => {
    fetchLogs();
    const interval = setInterval(fetchLogs, 5000);

    if (searchSessionId) {
      searchSession();
    }

    return () => clearInterval(interval);
  }, []); // Intentionally empty dependency array to only run on mount

  return (
    <div className="space-y-6 animate-in fade-in duration-500">
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="bg-gray-900 border border-gray-800 p-6 rounded-xl shadow-lg">
          <div className="text-gray-400 flex items-center gap-2 mb-2"><Activity size={18} /> Total Traces</div>
          <div className="text-4xl font-bold text-white">{logs.length}</div>
        </div>
        <div className="bg-gray-900 border border-gray-800 p-6 rounded-xl shadow-lg">
          <div className="text-gray-400 flex items-center gap-2 mb-2"><Database size={18} /> Avg Latency</div>
          <div className="text-4xl font-bold text-blue-400">
            {logs.length ? Math.round(logs.reduce((acc, curr) => acc + curr.durationMs, 0) / logs.length) : 0} <span className="text-xl">ms</span>
          </div>
        </div>
        <div className="bg-gray-900 border border-gray-800 p-6 rounded-xl shadow-lg">
          <div className="text-gray-400 flex items-center gap-2 mb-2"><Server size={18} /> Errors</div>
          <div className="text-4xl font-bold text-red-400">{logs.filter(l => l.statusCode >= 400).length}</div>
        </div>
      </div>

      <div className="bg-gray-900 border border-gray-800 p-6 rounded-xl shadow-lg mt-8">
        <h3 className="text-xl font-bold text-white mb-4">End-to-End Session Trace</h3>
        <p className="text-sm text-gray-400 mb-6">Enter a Session UUID to visualize its complete execution path across the entire stack.</p>

        <div className="flex gap-2 relative max-w-2xl mb-8">
          <input
            type="text"
            className="w-full bg-gray-950 border border-gray-700 rounded-lg pl-4 pr-12 py-3 focus:outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500 transition font-mono text-sm"
            placeholder="e.g. 123e4567-e89b-12d3..."
            value={searchSessionId}
            onChange={(e) => setSearchSessionId(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && searchSession()}
          />
          <button
            onClick={searchSession}
            disabled={!searchSessionId.trim()}
            className="absolute right-2 top-2 bottom-2 bg-blue-600 hover:bg-blue-500 text-white rounded px-4 transition disabled:opacity-50"
          >
            <Search size={18} />
          </button>
        </div>

        {sessionTraces !== null && (
          <div className="border border-gray-800 rounded-lg bg-gray-950 p-6">
            {sessionTraces.length === 0 ? (
              <div className="text-gray-500 text-center py-8">No trace history found for this session ID.</div>
            ) : (
              <div className="space-y-0 relative before:absolute before:inset-0 before:ml-5 before:-translate-x-px md:before:mx-auto md:before:translate-x-0 before:h-full before:w-0.5 before:bg-gradient-to-b before:from-transparent before:via-gray-700 before:to-transparent">
                {sessionTraces.map((trace, index) => (
                  <div key={index} className="relative flex items-center justify-between md:justify-normal md:odd:flex-row-reverse group is-active py-4">

                    {/* Diagram Node Icon */}
                    <div className="flex items-center justify-center w-10 h-10 rounded-full border border-gray-700 bg-gray-900 text-gray-400 shrink-0 md:order-1 md:group-odd:-translate-x-1/2 md:group-even:translate-x-1/2 shadow absolute left-0 md:left-1/2 z-10">
                      {trace.provider === 'Frontend' ? <Terminal size={18} className="text-blue-400" /> :
                        trace.provider === 'VoyageAI' ? <BrainCircuit size={18} className="text-purple-400" /> :
                          trace.provider === 'Ollama' ? <Server size={18} className="text-emerald-400" /> :
                            <Database size={18} className="text-gray-400" />}
                    </div>

                    {/* Content Card */}
                    <div className="w-[calc(100%-4rem)] md:w-[calc(50%-2.5rem)] p-4 rounded border border-gray-800 bg-gray-900 ml-14 md:ml-0 shadow-sm transition hover:border-gray-700">
                      <div className="flex items-center justify-between space-x-2 mb-1">
                        <div className="font-bold text-gray-200">{trace.provider}</div>
                        <div className={`text-xs font-mono font-bold px-2 py-0.5 rounded ${trace.statusCode >= 400 ? 'bg-red-500/20 text-red-400' : 'bg-green-500/20 text-green-400'}`}>
                          {trace.statusCode}
                        </div>
                      </div>
                      <div className="text-xs text-gray-400 mb-3">{trace.type}: {trace.endpoint}</div>

                      <div className="flex justify-between items-center mt-2 pt-2 border-t border-gray-800">
                        <span className="text-xs text-gray-500">{new Date(trace.timestamp).toLocaleTimeString([], { hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" })}.{new Date(trace.timestamp).getMilliseconds()} • {trace.durationMs}ms</span>

                        <button
                          onClick={() => setExpandedTraceId(expandedTraceId === index ? null : index)}
                          className="text-xs flex items-center gap-1 text-blue-400 hover:text-blue-300 transition"
                        >
                          {expandedTraceId === index ? 'Hide Payloads' : 'View Payloads'}
                          {expandedTraceId === index ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                        </button>
                      </div>

                      {/* Expandable Payloads */}
                      {expandedTraceId === index && (
                        <div className="mt-4 space-y-3 animate-in fade-in duration-300">
                          <div>
                            <div className="text-[10px] font-bold tracking-wider uppercase text-gray-500 mb-1">Request Payload</div>
                            <div className="bg-gray-950 p-2 rounded border border-gray-800 text-xs font-mono text-gray-300 overflow-x-auto whitespace-pre">
                              {trace.requestPayload || 'null'}
                            </div>
                          </div>
                          <div>
                            <div className="text-[10px] font-bold tracking-wider uppercase text-gray-500 mb-1">Response Payload</div>
                            <div className="bg-gray-950 p-2 rounded border border-gray-800 text-xs font-mono text-gray-300 overflow-x-auto whitespace-pre max-h-48">
                              {trace.responsePayload || 'null'}
                            </div>
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}