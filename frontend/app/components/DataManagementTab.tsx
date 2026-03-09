import React, { useState, useEffect } from 'react';
import { HardDriveDownload, Server, Database, Activity, CheckCircle2 } from 'lucide-react';

export default function DataManagementTab() {
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
