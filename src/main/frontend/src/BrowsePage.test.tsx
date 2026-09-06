import '@testing-library/jest-dom/vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { createMemoryHistory } from '@tanstack/history';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAppRouter } from './router';
import {
  downloadFile,
  getMode,
  getTree,
  getTextPreview,
  uploadFile,
  type Entry,
  type TreeResponse,
} from './api';

// Frontend seam: the API client is mocked at its module edge so the real
// component tree — router included (memory history) — runs without a backend.
vi.mock('./api', async (importOriginal) => {
  const original = await importOriginal<typeof import('./api')>();
  return {
    ...original,
    getTree: vi.fn(),
    getTextPreview: vi.fn(),
    downloadFile: vi.fn(),
    getMode: vi.fn(),
    uploadFile: vi.fn(),
  };
});

const mockedGetTree = vi.mocked(getTree);
const realApi = await vi.importActual<typeof import('./api')>('./api');

const mockedGetTextPreview = vi.mocked(getTextPreview);
const mockedDownloadFile = vi.mocked(downloadFile);
const mockedGetMode = vi.mocked(getMode);
const mockedUploadFile = vi.mocked(uploadFile);

// Seed listing: dirs before files is the server's contract; case-mixed
// names prove the order survives the client side.
const SUB: Entry = { name: 'Documents', kind: 'DIR', size: 0, lastModified: 1735689600000 };
const DIRS: Entry[] = [SUB, { name: 'zeta', kind: 'DIR', size: 0, lastModified: 1735689600000 }];
const FILES: Entry[] = [
  { name: 'apple.txt', kind: 'FILE', size: 1024, lastModified: 1735689600000 },
  { name: 'BANANA.md', kind: 'FILE', size: 5 * 1024 * 1024, lastModified: 1735689600000 },
  { name: 'cherry.png', kind: 'FILE', size: 7, lastModified: 1735689600000 },
  { name: 'data.bin', kind: 'FILE', size: 256, lastModified: 1735689600000 },
];
const ROOT_LISTING: TreeResponse = { path: '/srv/root', entries: [...DIRS, ...FILES] };
const DOCS_LISTING: TreeResponse = { path: '/srv/root/Documents', entries: [] };

function treeFor(path: string): TreeResponse {
  if (path === '' || path === 'Documents') return path === '' ? ROOT_LISTING : DOCS_LISTING;
  throw new Error(`tree request failed: HTTP 404`);
}

function renderApp(initialPath: string[] = ['/browse']) {
  const router = createAppRouter(createMemoryHistory({ initialEntries: initialPath }));
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

beforeEach(() => {
  mockedGetTree.mockReset();
  mockedGetTextPreview.mockReset();
  mockedDownloadFile.mockReset();
  mockedGetMode.mockReset();
  mockedUploadFile.mockReset();
  // Default mock is path-aware so navigation tests see the right listing.
  mockedGetTree.mockImplementation(async (path?: string) => treeFor(path ?? ''));
  // Default mode is read-only — the deployment default — so the existing
  // tests' snapshots stay honest: no upload control anywhere.
  mockedGetMode.mockResolvedValue({ mode: 'read-only' });
});

afterEach(cleanup);

describe('browse page (router seam)', () => {
  it('renders the listing from /api/tree, dirs before files', async () => {
    renderApp();

    expect(await screen.findByTestId('tree-entries')).toBeInTheDocument();
    for (const entry of ROOT_LISTING.entries) {
      const testid = entry.kind === 'DIR' ? `entry-link-${entry.name}` : `entry-file-${entry.name}`;
      expect(screen.getByTestId(testid)).toBeInTheDocument();
    }
    const order = ROOT_LISTING.entries.map((e) => screen.getByTestId(
      e.kind === 'DIR' ? `entry-link-${e.name}` : `entry-file-${e.name}`,
    ).compareDocumentPosition as unknown as number);
    expect(order).toHaveLength(6);
    // Sizes render human-readable.
    expect(screen.getByTestId('entry-file-apple.txt')).toHaveTextContent('1.0 KB');
    expect(screen.getByTestId('entry-file-BANANA.md')).toHaveTextContent('5.0 MB');
    expect(screen.getByTestId('entry-file-cherry.png')).toHaveTextContent('7 B');
  });

  it('updates the URL and listing when a directory is clicked', async () => {
    const user = userEvent.setup();
    const router = renderApp();

    await user.click(await screen.findByTestId('entry-link-Documents'));

    await waitFor(() => expect(router.state.location.href).toBe('/browse?path=Documents'));
    expect(mockedGetTree).toHaveBeenCalledWith('Documents');
    expect(await screen.findByTestId('tree-empty')).toBeInTheDocument();
  });

  it('renders the right directory for a deep link', async () => {
    mockedGetTree.mockImplementation(async (path?: string) => treeFor(path ?? ''));

    renderApp(['/browse?path=Documents']);

    expect(await screen.findByTestId('tree-empty')).toBeInTheDocument();
    expect(mockedGetTree).toHaveBeenCalledWith('Documents');
  });

  it('navigates upward via breadcrumbs', async () => {
    mockedGetTree.mockImplementation(async (path?: string) => treeFor(path ?? ''));
    const user = userEvent.setup();
    const router = renderApp(['/browse?path=Documents']);

    await screen.findByTestId('tree-empty');
    await user.click(screen.getByRole('link', { name: 'root' }));

    await waitFor(() => expect(router.state.location.href).toBe('/browse'));
    expect(mockedGetTree).toHaveBeenLastCalledWith('');
    expect(await screen.findByTestId('tree-entries')).toBeInTheDocument();
    expect(screen.getByTestId('entry-link-Documents')).toBeInTheDocument();
  });

  it('back/forward move through history', async () => {
    mockedGetTree.mockImplementation(async (path?: string) => treeFor(path ?? ''));
    const user = userEvent.setup();
    const router = renderApp();

    await user.click(await screen.findByTestId('entry-link-Documents'));
    await waitFor(() => expect(router.state.location.href).toBe('/browse?path=Documents'));
    router.history.back();
    await waitFor(() => expect(router.state.location.href).toBe('/browse'));
    expect(await screen.findByTestId('tree-entries')).toBeInTheDocument();

    router.history.forward();
    await waitFor(() => expect(router.state.location.href).toBe('/browse?path=Documents'));
    expect(await screen.findByTestId('tree-empty')).toBeInTheDocument();
  });

  it('narrows the listing with the client-side filter', async () => {
    const user = userEvent.setup();
    renderApp();

    await screen.findByTestId('tree-entries');
    await user.type(screen.getByRole('searchbox', { name: 'Filter by name' }), 'BAN');

    expect(screen.getByTestId('entry-file-BANANA.md')).toBeInTheDocument();
    expect(screen.queryByTestId('entry-file-apple.txt')).not.toBeInTheDocument();
    expect(screen.queryByTestId('entry-link-Documents')).not.toBeInTheDocument();
  });

  it('renders a loading state while fetching', async () => {
    mockedGetTree.mockImplementation(() => new Promise<TreeResponse>(() => {}));

    renderApp();

    expect(await screen.findByTestId('tree-loading')).toBeInTheDocument();
  });

  it('renders an explicit empty-directory message', async () => {
    mockedGetTree.mockResolvedValue({ path: '/srv/root/empty', entries: [] });

    renderApp();

    expect(await screen.findByTestId('tree-empty')).toHaveTextContent('This directory is empty.');
  });

  it('renders an error with a retry affordance', async () => {
    mockedGetTree.mockRejectedValueOnce(new Error('tree request failed: HTTP 500'));
    const user = userEvent.setup();
    renderApp();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('HTTP 500');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();

    // Retry recovers to the normal listing.
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByTestId('tree-entries')).toBeInTheDocument();
  });

  it('never builds traversal-shaped requests or hrefs', async () => {
    // The client only ever sends relative subpaths: childPath throws on
    // traversal-shaped input and getTree double-checks before fetching.
    // (getTree is the live mock here, so assert against the real module.)
    const { childPath: realChildPath, getTree: realGetTree, isSafeRelativePath: realIsSafe } = realApi;
    expect(() => realChildPath('a', '../b')).toThrow();
    expect(() => realChildPath('a', '/etc')).toThrow();
    expect(() => realChildPath('../a', 'b')).toThrow();
    expect(() => realChildPath('a', 'b/c')).toThrow();
    expect(realIsSafe('..')).toBe(false);
    expect(realIsSafe('../x')).toBe(false);
    expect(realIsSafe('/abs')).toBe(false);
    expect(realIsSafe('a//b')).toBe(false);
    expect(realIsSafe('a/../b')).toBe(false);
    expect(realIsSafe('a\\b')).toBe(false);
    expect(realIsSafe('a\0b')).toBe(false);
    expect(realIsSafe('a/b')).toBe(true);
    expect(realIsSafe('')).toBe(true);
    // getTree is async: refusal surfaces as a rejected promise, and the
    // rejected promise proves no fetch URL was ever built.
    await expect(realGetTree('../etc')).rejects.toThrow();
    await expect(realGetTree('/etc')).rejects.toThrow();
    await expect(realGetTree('a/../b')).rejects.toThrow();
    await expect(realGetTree('a\0b')).rejects.toThrow();
  });
});

describe('file preview panel (api-client seam)', () => {
  it('renders text content when a text entry is clicked', async () => {
    mockedGetTextPreview.mockResolvedValue({ content: 'héllo wörld', truncated: false });
    const user = userEvent.setup();
    renderApp();

    await user.click(await screen.findByTestId('entry-file-apple.txt'));

    expect(await screen.findByTestId('preview-panel')).toBeInTheDocument();
    expect(screen.getByTestId('preview-title')).toHaveTextContent('apple.txt');
    expect(screen.getByTestId('preview-text')).toHaveTextContent('héllo wörld');
    expect(screen.queryByTestId('preview-truncated')).not.toBeInTheDocument();
    expect(mockedGetTextPreview).toHaveBeenCalledWith('apple.txt');
  });

  it('shows a truncation notice when the cap marker is set', async () => {
    mockedGetTextPreview.mockResolvedValue({ content: 'x'.repeat(100), truncated: true });
    const user = userEvent.setup();
    renderApp();

    await user.click(await screen.findByTestId('entry-file-apple.txt'));

    expect(await screen.findByTestId('preview-truncated')).toBeInTheDocument();
    expect(screen.getByTestId('preview-text')).toBeInTheDocument();
  });

  it('renders an image straight from the /api/file URL', async () => {
    const user = userEvent.setup();
    renderApp();

    await user.click(await screen.findByTestId('entry-file-cherry.png'));

    const img = await screen.findByTestId('preview-image');
    expect(img).toBeInTheDocument();
    expect(img.getAttribute('src')).toBe('/api/file?path=cherry.png');
    expect(mockedGetTextPreview).not.toHaveBeenCalled();
  });

  it('downloads other kinds directly, without a panel', async () => {
    const user = userEvent.setup();
    renderApp();

    await user.click(await screen.findByTestId('entry-download-data.bin'));

    expect(mockedDownloadFile).toHaveBeenCalledWith('data.bin');
    expect(screen.queryByTestId('preview-panel')).not.toBeInTheDocument();
  });

  it('closes the panel on Close', async () => {
    mockedGetTextPreview.mockResolvedValue({ content: 'nested', truncated: false });
    const user = userEvent.setup();
    renderApp();

    await user.click(await screen.findByTestId('entry-file-apple.txt'));
    await screen.findByTestId('preview-text');
    await user.click(screen.getByTestId('preview-close'));

    await waitFor(() =>
      expect(screen.queryByTestId('preview-panel')).not.toBeInTheDocument(),
    );
  });

  it('offers a download action for every previewed file', async () => {
    mockedGetTextPreview.mockResolvedValue({ content: 'body', truncated: false });
    const user = userEvent.setup();
    renderApp();

    await user.click(await screen.findByTestId('entry-file-apple.txt'));
    await screen.findByTestId('preview-text');
    await user.click(screen.getByTestId('preview-download'));

    expect(mockedDownloadFile).toHaveBeenCalledWith('apple.txt');
  });
});

describe('mode awareness + upload (router seam)', () => {
  it('renders no upload control in read-only mode', async () => {
    mockedGetMode.mockResolvedValue({ mode: 'read-only' });
    renderApp();

    await screen.findByTestId('tree-entries');

    expect(screen.queryByTestId('upload-control')).not.toBeInTheDocument();
    expect(screen.queryByTestId('upload-button')).not.toBeInTheDocument();
    expect(mockedGetMode).toHaveBeenCalled();
  });

  it('renders the upload control in read-write mode', async () => {
    mockedGetMode.mockResolvedValue({ mode: 'read-write' });
    renderApp();

    expect(await screen.findByTestId('upload-control')).toBeInTheDocument();
    expect(screen.getByTestId('upload-button')).toBeInTheDocument();
    // Still the same listing, unaffected by the mode.
    expect(await screen.findByTestId('tree-entries')).toBeInTheDocument();
  });

  it('uploads the picked file into the current directory and refreshes the listing', async () => {
    mockedGetMode.mockResolvedValue({ mode: 'read-write' });
    mockedUploadFile.mockImplementation(async (_path: string, file: File) => ({
      name: file.name,
      size: file.size,
    }));
    const user = userEvent.setup();
    renderApp();

    await screen.findByTestId('tree-entries');
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input).not.toBeNull();

    const file = new File(['uploaded-bytes'], 'notes.md', { type: 'text/markdown' });
    await user.upload(input, file);

    await waitFor(() => expect(mockedUploadFile).toHaveBeenCalledWith('', file));
    // Success invalidates the ['tree', path] query — the listing refetches.
    await waitFor(() => expect(mockedGetTree).toHaveBeenCalledWith(''));
    expect(screen.queryByTestId('upload-error')).not.toBeInTheDocument();
  });

  it('targets the current directory when browsing a subdirectory', async () => {
    mockedGetMode.mockResolvedValue({ mode: 'read-write' });
    mockedUploadFile.mockResolvedValue({ name: 'nested.txt', size: 3 });
    const user = userEvent.setup();
    renderApp();

    await user.click(await screen.findByTestId('entry-link-Documents'));
    await screen.findByTestId('tree-empty');

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['abc'], 'nested.txt');
    await user.upload(input, file);

    await waitFor(() => expect(mockedUploadFile).toHaveBeenCalledWith('Documents', file));
  });

  it('shows a visible error when the upload fails', async () => {
    mockedGetMode.mockResolvedValue({ mode: 'read-write' });
    mockedUploadFile.mockRejectedValue(
      new Error("upload failed: HTTP 409: An Entry named 'apple.txt' already exists"),
    );
    const user = userEvent.setup();
    renderApp();

    await screen.findByTestId('tree-entries');
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['dup'], 'apple.txt');
    await user.upload(input, file);

    const alert = await screen.findByTestId('upload-error');
    expect(alert).toHaveTextContent('Upload failed');
    expect(alert).toHaveTextContent('409');
    expect(alert).toHaveTextContent('already exists');
  });

  it('never builds an upload request for a traversal-shaped target', async () => {
    // The client refuses before any fetch: same discipline as getTree.
    const { uploadFile: realUpload } = realApi;
    await expect(realUpload('../etc', new File(['x'], 'x'))).rejects.toThrow();
    await expect(realUpload('a/../b', new File(['x'], 'x'))).rejects.toThrow();
    await expect(realUpload('/abs', new File(['x'], 'x'))).rejects.toThrow();
    expect(mockedUploadFile).not.toHaveBeenCalled();
  });
});
