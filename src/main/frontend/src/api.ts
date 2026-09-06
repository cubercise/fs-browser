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
