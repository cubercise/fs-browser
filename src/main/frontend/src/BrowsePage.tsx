import { useMemo, useState } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { Breadcrumbs, Button, Spinner, SearchField } from '@heroui/react';
import { Folder, File as FileIcon, Download } from '@phosphor-icons/react';
import { browseRoute } from './router';
import {
  childPath,
  downloadFile,
  getMode,
  getTree,
  predictFileKind,
  segments,
  type Entry,
} from './api';
import { formatBytes, formatTimestamp } from './format';
import FilePreview from './FilePreview';
import UploadControl from './UploadControl';
import EntryActions from './EntryActions';

/**
 * The browse page: one directory per URL (`/browse?path=…`). The listing is
 * fetched with TanStack Query keyed by the path; navigation happens through
 * the router only, with targets built exclusively from relative subpaths
 * (childPath refuses anything traversal-shaped). The instance Mode is
 * fetched once — in read-write mode the toolbar gains an upload control
 * and each row gains a rename/delete actions menu, in read-only mode no
 * write UI exists at all.
 */
export default function BrowsePage() {
  const { path = '' } = browseRoute.useSearch();
  const query = useQuery({
    queryKey: ['tree', path],
    queryFn: () => getTree(path),
  });
  const modeQuery = useQuery({
    queryKey: ['mode'],
    queryFn: getMode,
    staleTime: Infinity,
  });
  const writable = modeQuery.data?.mode === 'read-write';

  const [filter, setFilter] = useState('');
  const [preview, setPreview] = useState<{ path: string; entry: Entry } | null>(null);

  const visible = useMemo(() => {
    const entries = query.data?.entries ?? [];
    const needle = filter.trim().toLowerCase();
    return needle ? entries.filter((e) => e.name.toLowerCase().includes(needle)) : entries;
  }, [query.data, filter]);

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-3xl flex-col gap-4 px-4 py-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">fs-browser</h1>
        {/* Small liveness affordance; the /api/ping contract itself is untouched. */}
        <a className="text-xs text-muted hover:underline" href="/api/ping" target="_blank" rel="noreferrer">
          ping
        </a>
      </header>

      <PathBreadcrumbs path={path} />

      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <SearchField
            name="filter"
            aria-label="Filter by name"
            variant="secondary"
            value={filter}
            onChange={setFilter}
          >
            <SearchField.Group>
              <SearchField.SearchIcon />
              <SearchField.Input placeholder="Filter by name…" />
              <SearchField.ClearButton />
            </SearchField.Group>
          </SearchField>
        </div>
        {/* Absent — not disabled — unless the instance is read-write. */}
        {writable && <UploadControl path={path} />}
      </div>

      {query.isPending ? (
        <div className="flex items-center gap-3 text-sm text-muted" data-testid="tree-loading">
          <Spinner size="sm" aria-label="Loading directory" />
          <span>Loading…</span>
        </div>
      ) : query.isError ? (
        <div role="alert" className="flex flex-col items-start gap-3">
          <p className="text-sm text-red-500">
            Could not load {path === '' ? 'the Root' : `'${path}'`}:
            {query.error instanceof Error ? ` ${query.error.message}` : ` ${String(query.error)}`}
          </p>
          <Button variant="secondary" onPress={() => void query.refetch()}>
            Retry
          </Button>
        </div>
      ) : visible.length === 0 ? (
        <p className="text-sm text-muted" data-testid="tree-empty">
          {filter ? 'No entries match the filter.' : 'This directory is empty.'}
        </p>
      ) : (
        <ul className="flex flex-col divide-y divide-default" data-testid="tree-entries">
          {visible.map((entry) => (
            <EntryRow key={entry.name} entry={entry} path={path} onOpenFile={setPreview} writable={writable} />
          ))}
        </ul>
      )}

      {preview && (
        <FilePreview
          file={preview}
          kind={predictFileKind(preview.entry.name)}
          onClose={() => setPreview(null)}
        />
      )}
    </div>
  );
}

function PathBreadcrumbs({ path }: { path: string }) {
  const navigate = useNavigate();
  const crumbs = useMemo(() => {
    const segs = segments(path);
    return [
      { label: 'root', path: '' },
      ...segs.map((segment, i) => ({ label: segment, path: segs.slice(0, i + 1).join('/') })),
    ];
  }, [path]);

  return (
    <nav aria-label="Directory path">
      <Breadcrumbs>
        {crumbs.map((crumb, index) =>
          index === crumbs.length - 1 ? (
            <Breadcrumbs.Item key={crumb.path}>{crumb.label}</Breadcrumbs.Item>
          ) : (
            <Breadcrumbs.Item
              key={crumb.path}
              onPress={() =>
                void navigate({
                  to: '/browse',
                  // Omit the param entirely at the Root so the URL is clean.
                  search: crumb.path ? { path: crumb.path } : {},
                })
              }
            >
              {crumb.label}
            </Breadcrumbs.Item>
          ),
        )}
      </Breadcrumbs>
    </nav>
  );
}

function EntryRow({
  entry,
  path,
  onOpenFile,
  writable,
}: {
  entry: Entry;
  path: string;
  onOpenFile: (file: { path: string; entry: Entry }) => void;
  writable: boolean;
}) {
  const navigate = useNavigate();
  // Client-side guard: only relative subpaths are ever turned into targets,
  // so no traversal-shaped href/request can be constructed here.
  const target = childPath(path, entry.name);

  if (entry.kind === 'FILE') {
    const kind = predictFileKind(entry.name);
    if (kind === 'BINARY') {
      // Unknown/binary content: no preview exists, go straight to download.
      return (
        <li className="flex items-center gap-3 px-2 py-2" data-testid={`entry-file-${entry.name}`}>
          <FileIcon aria-hidden className="text-muted" />
          <EntryMeta entry={entry} />
          {writable && <EntryActions entry={entry} path={path} />}
          <Button
            variant="secondary"
            aria-label={`Download ${entry.name}`}
            data-testid={`entry-download-${entry.name}`}
            onPress={() => downloadFile(target)}
          >
            <Download aria-hidden />
          </Button>
        </li>
      );
    }
    return (
      <li>
        <button
          type="button"
          className="flex w-full cursor-pointer items-center gap-3 px-2 py-2 text-left hover:bg-default"
          data-testid={`entry-file-${entry.name}`}
          onClick={() => onOpenFile({ path: target, entry })}
        >
          <FileIcon aria-hidden className="text-muted" />
          <EntryMeta entry={entry} />
          {writable && <EntryActions entry={entry} path={path} />}
        </button>
      </li>
    );
  }

  return (
    <li>
      <div className="flex items-center">
        <button
          type="button"
          className="flex min-w-0 flex-1 cursor-pointer items-center gap-3 px-2 py-2 text-left hover:bg-default"
          data-testid={`entry-link-${entry.name}`}
          onClick={() => void navigate({ to: '/browse', search: { path: target } })}
        >
          <Folder aria-hidden className="text-accent" />
          <EntryMeta entry={entry} />
        </button>
        {writable && <EntryActions entry={entry} path={path} />}
      </div>
    </li>
  );
}

function EntryMeta({ entry }: { entry: Entry }) {
  return (
    <>
      <span className="truncate font-medium">{entry.name}</span>
      <span className="ml-auto shrink-0 text-xs text-muted">{formatBytes(entry.size)}</span>
      <span className="hidden shrink-0 text-xs text-muted sm:inline">
        {formatTimestamp(entry.lastModified)}
      </span>
    </>
  );
}
