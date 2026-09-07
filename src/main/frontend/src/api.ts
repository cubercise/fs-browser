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

/**
 * True when `name` is a plain Entry name — no path separator, no null
 * byte, not a dot/dot-dot name — the client-side mirror of the fence
 * around every write. The server's EntryStore stays the authority; this
 * only stops the request from ever being built.
 */
export function isPlainEntryName(name: string): boolean {
  return name !== '' && name !== '.' && name !== '..' && !name.includes('/') && !name.includes('\\') && !name.includes('\0');
}

/**
 * Extension-based prediction of what /api/file will serve, used ONLY to
 * route the UI (open the preview panel vs download directly). The
 * backend's content sniffing stays the authority for what is actually
 * served — a mispredicted kind corrects itself in the panel, which
 * consults the served Content-Type (see getTextPreview).
 */
const IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'] as const;
const TEXT_EXTENSIONS = [
  'txt', 'md', 'json', 'csv', 'log', 'xml', 'yml', 'yaml', 'ini', 'conf', 'html', 'css', 'js', 'ts',
] as const;

/** The predicted FileKind for an Entry name (UI routing hint, not authority). */
export function predictFileKind(name: string): FileKind {
  const dot = name.lastIndexOf('.');
  const ext = dot === -1 ? '' : name.slice(dot + 1).toLowerCase();
  if ((IMAGE_EXTENSIONS as readonly string[]).includes(ext)) {
    return 'IMAGE';
  }
  if ((TEXT_EXTENSIONS as readonly string[]).includes(ext)) {
    return 'TEXT';
  }
  return 'BINARY';
}

/** The renamed Entry, as reported by a successful POST /api/rename. */
export interface RenameResult {
  name: string;
}

/**
 * Renames one Entry (file or directory) inside a directory of the Root
 * via POST /api/rename (JSON {"path": …, "from": …, "to": …}). Same
 * traversal-refusal discipline as uploadFile — the client refuses to even
 * build the request for a traversal-shaped directory or a pathy name; the
 * server's Sandbox + EntryStore stay the authority. Errors carry the
 * server's {"error": …} detail when present (409 duplicate target, 400
 * bad name, 404 missing source, 403 read-only).
 */
export async function renameEntry(path: string, from: string, to: string): Promise<RenameResult> {
  if (!isSafeRelativePath(path) || !isPlainEntryName(from) || !isPlainEntryName(to)) {
    throw new Error(`Refusing to rename with a traversal-shaped path or name: ${JSON.stringify([path, from, to])}`);
  }
  const response = await fetch('/api/rename', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, from, to }),
  });
  if (!response.ok) {
    throw new Error(`rename failed: ${await errorDetail(response)}`);
  }
  return (await response.json()) as RenameResult;
}

/**
 * Deletes one Entry (file or empty directory) from a directory of the
 * Root via DELETE /api/file?path=…&name=…. Same fences as renameEntry;
 * success is 204 (no body). Errors carry the server's {"error": …}
 * detail when present (409 non-empty directory, 404 missing, 400 bad
 * name, 403 read-only).
 */
export async function deleteEntry(path: string, name: string): Promise<void> {
  if (!isSafeRelativePath(path) || !isPlainEntryName(name)) {
    throw new Error(`Refusing to delete with a traversal-shaped path or name: ${JSON.stringify([path, name])}`);
  }
  const response = await fetch(`/api/file?path=${encodeURIComponent(path)}&name=${encodeURIComponent(name)}`, {
    method: 'DELETE',
  });
  if (!response.ok) {
    throw new Error(`delete failed: ${await errorDetail(response)}`);
  }
}

/** Extracts the server's {"error": …} body, falling back to the status. */
async function errorDetail(response: Response): Promise<string> {
  let detail = `HTTP ${response.status}`;
  try {
    const payload = (await response.json()) as { error?: string };
    if (payload.error) {
      detail = `${detail}: ${payload.error}`;
    }
  } catch {
    // Body wasn't JSON — the status alone is the message.
  }
  return detail;
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
  /**
   * False when the server's sniff refused to serve this as text (e.g. a
   * .txt that is really a PNG) — `content` is then empty and the UI must
   * not render it; the panel falls back to image/download handling.
   */
  servedAsText: boolean;
}

/**
 * Fetches a file for text preview: GET /api/file as text, surfacing the
 * truncation marker — and the served Content-Type, because the backend's
 * content sniffing is the authority on what a file really is. A .txt full
 * of PNG bytes comes back as image/png, and this client reports
 * `served: false` so the UI can fall through to image/download handling
 * instead of rendering binary garbage in a <pre>. Same traversal-refusal
 * discipline as getTree — the server's Sandbox stays the authority, this
 * is the client's fence.
 */
export async function getTextPreview(path: string): Promise<TextPreview> {
  if (!isSafeRelativePath(path)) {
    throw new Error(`Refusing to request a traversal-shaped path: ${JSON.stringify(path)}`);
  }
  const response = await fetch(`/api/file?path=${encodeURIComponent(path)}`);
  if (!response.ok) {
    throw new Error(`file request failed: HTTP ${response.status}`);
  }
  const contentType = (response.headers.get('Content-Type') ?? '').toLowerCase();
  const servedAsText = contentType.startsWith('text/');
  return {
    content: servedAsText ? await response.text() : '',
    truncated: response.headers.get('X-Fsb-Truncated') === 'true',
    servedAsText,
  };
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
