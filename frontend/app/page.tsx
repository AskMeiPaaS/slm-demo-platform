'use client';

import React, { useState, useEffect } from 'react';
import { Terminal, Activity, BrainCircuit, HardDriveDownload, Server } from 'lucide-react';
import DataManagementTab from './components/DataManagementTab';
import ChatInterface from './components/ChatInterface';
import ObservabilityDashboard from './components/ObservabilityDashboard';

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
            <h1 className="text-xl font-bold tracking-tight">SLM Platform <span className="text-xs bg-emerald-500/20 text-emerald-400 px-2 py-1 rounded">Llama 3.1:8b</span></h1>
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