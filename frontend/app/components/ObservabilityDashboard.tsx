import React, { useState, useEffect } from 'react';
import { Activity, Database, Server, Terminal, BrainCircuit, ChevronDown, ChevronRight, Search } from 'lucide-react';

export default function ObservabilityDashboard({ searchSessionId, setSearchSessionId }: { searchSessionId: string, setSearchSessionId: (id: string) => void }) {
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
