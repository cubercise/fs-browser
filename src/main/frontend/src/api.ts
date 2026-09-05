/** Client for the fs-browser backend API. */

export interface PingResponse {
  pong: boolean;
  /** The Root directory this app browses, as reported by the backend. */
  root: string;
}

export async function ping(): Promise<PingResponse> {
  const response = await fetch('/api/ping');
  if (!response.ok) {
    throw new Error(`ping failed: HTTP ${response.status}`);
  }
  return (await response.json()) as PingResponse;
}
