import { useCallback, useEffect, useState } from 'react';
import { Button } from '@heroui/react';
import { ping, type PingResponse } from './api';

/**
 * Walking-skeleton page: proves the front-to-back path by calling
 * GET /api/ping on load and rendering the Root it reports.
 */
export default function App() {
  const [pingResponse, setPingResponse] = useState<PingResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setError(null);
    ping()
      .then(setPingResponse)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <main className="bg-gradient-to-br from-slate-950 to-slate-800 flex min-h-screen flex-col items-center justify-center gap-6 text-white">
      <h1 className="text-3xl font-bold">fs-browser</h1>
      <p className="text-slate-300">walking skeleton — front-to-back ping</p>
      {error !== null && (
        <p role="alert" className="text-red-400">
          {error}
        </p>
      )}
      {pingResponse !== null ? (
        <div className="rounded-xl border border-white/10 bg-white/5 px-6 py-4 font-mono">
          <p data-testid="ping-pong">pong: {String(pingResponse.pong)}</p>
          <p data-testid="ping-root">Root: {pingResponse.root}</p>
        </div>
      ) : (
        error === null && <p data-testid="ping-loading">pinging backend…</p>
      )}
      <Button variant="primary" onPress={load}>
        Re-ping
      </Button>
    </main>
  );
}
