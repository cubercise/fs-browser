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

/** The deployment-wide permission switch, as reported by GET /api/mode. */
export type AppMode = 'read-only' | 'read-write';

export interface ModeResponse {
  mode: AppMode;
}

/**
 * Fetches the instance Mode once per app load: the UI uses it to decide
 * whether write affordances exist at all (in read-only mode the upload
 * control is absent, not disabled).
 */
export async function getMode(): Promise<ModeResponse> {
  const response = await fetch('/api/mode');
  if (!response.ok) {
    throw new Error(`mode request failed: HTTP ${response.status}`);
  }
  return (await response.json()) as ModeResponse;
}

/** The stored Entry, as reported by a successful POST /api/upload. */
export interface UploadResult {
  name: string;
  size: number;
}

/**
 * Uploads one file into a directory of the Root via POST /api/upload
 * (multipart). Same traversal-refusal discipline as getTree — the client
 * refuses to even build the request for a pathy target; the server's
 * Sandbox + write guard stay the authority. Errors carry the server's
 * {"error": …} detail when present (409 duplicate name, 400 bad name, …).
 */
export async function uploadFile(path: string, file: File): Promise<UploadResult> {
  if (!isSafeRelativePath(path)) {
    throw new Error(`Refusing to upload to a traversal-shaped path: ${JSON.stringify(path)}`);
  }
  const body = new FormData();
  body.append('file', file);
  const query = path ? `?path=${encodeURIComponent(path)}` : '';
  const response = await fetch(`/api/upload${query}`, { method: 'POST', body });
  if (!response.ok) {
    let detail = `HTTP ${response.status}`;
    try {
      const payload = (await response.json()) as { error?: string };
      if (payload.error) {
        detail = `${detail}: ${payload.error}`;
      }
    } catch {
      // Body wasn't JSON — the status alone is the message.
    }
    throw new Error(`upload failed: ${detail}`);
  }
  return (await response.json()) as UploadResult;
}

export type EntryKind = 'DIR' | 'FILE';

export interface Entry {
  name: string;
  kind: EntryKind;
  /** Bytes; the backend reports 0 for directories. */
  size: number;
  /** Epoch millis. */
  lastModified: number;
}

export interface TreeResponse {
  /** Normalized absolute directory path, as resolved by the backend. */
  path: string;
  entries: Entry[];
}

/**
 * True when `path` is a safe *relative* subpath of the Root: no leading
 * slash, no dot-dot segment, no empty (double-slash) segment, no backslash,
 * no null byte. The client refuses to even build — never mind send —
 * requests for anything else; the server's Sandbox remains the authority.
 */
export function isSafeRelativePath(path: string): boolean {
  if (path === '') {
    return true;
  }
  if (path.includes('\0') || path.includes('\\') || path.startsWith('/')) {
    return false;
  }
  return path.split('/').every((segment) => segment !== '..' && segment !== '');
}

/**
 * Joins a directory name from the listing onto the current path. Throws on
 * anything traversal-shaped so no hostile href can ever be constructed.
 */
export function childPath(parent: string, name: string): string {
  if (!isSafeRelativePath(parent) || !isSafeRelativePath(name) || name === '.' || name.includes('/')) {
    throw new Error(`Refusing to build a traversal-shaped path: ${JSON.stringify(`${parent}/${name}`)}`);
  }
  return parent ? `${parent}/${name}` : name;
}

/** Parent directory as a relative path ('' when already at the Root). */
export function parentPath(path: string): string {
  const index = path.lastIndexOf('/');
  return index === -1 ? '' : path.slice(0, index);
}

/** Path segments for breadcrumbs: segments('a/b') === ['a', 'b']. */
export function segments(path: string): string[] {
  return path ? path.split('/') : [];
}

export async function getTree(path = ''): Promise<TreeResponse> {
  if (!isSafeRelativePath(path)) {
    throw new Error(`Refusing to request a traversal-shaped path: ${JSON.stringify(path)}`);
  }
  const query = path ? `?path=${encodeURIComponent(path)}` : '';
  const response = await fetch(`/api/tree${query}`);
  if (!response.ok) {
    throw new Error(`tree request failed: HTTP ${response.status}`);
  }
  return (await response.json()) as TreeResponse;
}

/** What /api/file serves for a given path (client mirror of its response shapes). */
export type FileKind = 'TEXT' | 'IMAGE' | 'BINARY';

export interface TextPreview {
  content: string;
  truncated: boolean;
}

/**
 * Fetches a file for text preview: GET /api/file as text, surfacing the
 * truncation marker. Same traversal-refusal discipline as getTree — the
 * server's Sandbox stays the authority, this is the client's fence.
 */
export async function getTextPreview(path: string): Promise<TextPreview> {
  if (!isSafeRelativePath(path)) {
    throw new Error(`Refusing to request a traversal-shaped path: ${JSON.stringify(path)}`);
  }
  const response = await fetch(`/api/file?path=${encodeURIComponent(path)}`);
  if (!response.ok) {
    throw new Error(`file request failed: HTTP ${response.status}`);
  }
  return { content: await response.text(), truncated: response.headers.get('X-Fsb-Truncated') === 'true' };
}

/**
 * URL for a file served by /api/file, encoded per segment. Used for image
 * previews (<img src>) and downloads (anchor href) — a URL the browser
 * fetches, not a client-fetched path string, so it does not go through
 * isSafeRelativePath; the Sandbox still vets it server-side. (If the path
 * were traversal-shaped the browser would merely receive a 400/404 image.)
 */
export function fileUrl(path: string): string {
  return `/api/file?path=${encodeURIComponent(path)}`;
}

/**
 * Triggers a browser download of a file through /api/file. Returns the
 * constructed URL so tests can assert on it without a real anchor.
 */
export function downloadFile(path: string): string {
  const url = fileUrl(path);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = '';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  return url;
}
