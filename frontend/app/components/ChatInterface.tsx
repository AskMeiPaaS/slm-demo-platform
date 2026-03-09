import React, { useState, useEffect, useRef } from 'react';
import { Server, Send, BrainCircuit, Terminal, Activity, Database, CheckCircle2 } from 'lucide-react';

export default function ChatInterface({ chatSessionId }: { chatSessionId: string }) {
    const [prompt, setPrompt] = useState('');
    const [messages, setMessages] = useState<{ role: string, content: string, timeTakenMs?: number }[]>([]);
    const [loading, setLoading] = useState(false);
    const messagesEndRef = useRef<HTMLDivElement>(null);

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

    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages]);

    const executeAgent = async () => {
        if (!prompt.trim()) return;
        const userMsg = prompt;
        setMessages(prev => [...prev, { role: 'user', content: userMsg }, { role: 'agent', content: '' }]);
        setPrompt('');
        setLoading(true);

        try {
            const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api'}/v1/agent/execute`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    prompt: userMsg,
                    agentId: 'demo-agent-1',
                    modelName: 'llama3.1:8b', // Keep matching architecture update
                    sessionId: chatSessionId
                })
            });

            if (!res.body) throw new Error("ReadableStream not yet supported in this browser.");
            const reader = res.body.getReader();
            const decoder = new TextDecoder("utf-8");

            let done = false;
            let buffer = "";
            let currentEventName = "message";

            while (!done) {
                const { value, done: readerDone } = await reader.read();
                done = readerDone;
                if (value) {
                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split("\n");
                    buffer = lines.pop() || "";

                    for (const line of lines) {
                        if (line.startsWith("event:")) {
                            currentEventName = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            const dataStr = line.substring(5).trim();
                            if (!dataStr) continue;
                            try {
                                const data = JSON.parse(dataStr);
                                if (currentEventName === "chunk") {
                                    setMessages(prev => {
                                        const newMsgs = [...prev];
                                        newMsgs[newMsgs.length - 1] = { ...newMsgs[newMsgs.length - 1], content: newMsgs[newMsgs.length - 1].content + data.chunk };
                                        return newMsgs;
                                    });
                                } else if (currentEventName === "status") {
                                    setMessages(prev => {
                                        const newMsgs = [...prev];
                                        newMsgs[newMsgs.length - 1] = { ...newMsgs[newMsgs.length - 1], content: newMsgs[newMsgs.length - 1].content + `\n*[SYSTEM: ${data.message}]*\n\n` };
                                        return newMsgs;
                                    });
                                } else if (currentEventName === "done") {
                                    setMessages(prev => {
                                        const newMsgs = [...prev];
                                        newMsgs[newMsgs.length - 1] = { ...newMsgs[newMsgs.length - 1], timeTakenMs: data.timeTakenMs };
                                        return newMsgs;
                                    });
                                }
                            } catch (e) {
                                console.error("Error parsing SSE data line", e);
                            }
                        }
                    }
                }
            }
        } catch (error) {
            setMessages(prev => {
                const newMsgs = [...prev];
                newMsgs[newMsgs.length - 1] = { ...newMsgs[newMsgs.length - 1], content: newMsgs[newMsgs.length - 1].content + "\n\n[Connection Error: Backend unreachable]" };
                return newMsgs;
            });
        }
        setLoading(false);
    };

    return (
        <div className="flex flex-col h-[80vh] bg-gray-900 rounded-xl border border-gray-800 overflow-hidden shadow-2xl animate-in fade-in duration-500">
            <div className="flex-1 overflow-y-auto p-6 space-y-6">
                {messages.length === 0 && (
                    <div className="text-center text-gray-500 mt-20">
                        <BrainCircuit size={48} className="mx-auto mb-4 opacity-50 text-emerald-500" />
                        <p>Llama 3.1 8B Agent is ready. Ask a question about the movies loaded in your database!</p>
                        <p className="text-xs mt-2 opacity-50 font-mono">Session UUID: {chatSessionId}</p>
                    </div>
                )}
                {messages.map((msg, i) => (
                    <div key={i} className={`flex gap-4 ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                        <div className={`max-w-[80%] p-4 rounded-lg ${msg.role === 'user' ? 'bg-emerald-600/20 border border-emerald-500/30 text-emerald-100' :
                            msg.role === 'system' ? 'bg-red-500/20 border border-red-500/30 text-red-200' :
                                'bg-gray-800 border border-gray-700 text-gray-200'
                            }`}>
                            <div className="text-xs opacity-50 mb-1 uppercase tracking-wider font-bold shadow-sm">
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
                <div ref={messagesEndRef} />
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
