import { useQuery } from '@tanstack/react-query';
import { Button, Modal, Spinner } from '@heroui/react';
import { Download, WarningCircle } from '@phosphor-icons/react';
import { downloadFile, fileUrl, getTextPreview, type FileKind } from './api';
import { formatBytes } from './format';
import type { Entry } from './api';

/**
 * The one preview panel: a HeroUI Modal whose body depends on the file kind
 * the backend's content sniffing reported — text renders in a scrollable
 * <pre> (TanStack Query keyed by path, truncation surfaced as a notice plus
 * a download button), images render straight from the /api/file URL. A
 * download action is available for every file in the panel.
 *
 * Binary files never reach this panel; their entry click downloads directly.
 */
export default function FilePreview({
  file,
  kind,
  onClose,
}: {
  file: { path: string; entry: Entry };
  kind: FileKind;
  onClose: () => void;
}) {
  const { entry, path } = file;
  return (
    <Modal>
      <Modal.Backdrop isOpen onOpenChange={(open) => !open && onClose()}>
        <Modal.Container size="lg" scroll="inside">
          <Modal.Dialog aria-label={`Preview of ${entry.name}`} data-testid="preview-panel">
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading className="truncate" data-testid="preview-title">
                {entry.name}
              </Modal.Heading>
              <span className="shrink-0 text-xs text-muted">{formatBytes(entry.size)}</span>
            </Modal.Header>
            <Modal.Body>
              {kind === 'IMAGE' ? <ImagePreview path={path} name={entry.name} /> : <TextPreviewBody path={path} />}
            </Modal.Body>
            <Modal.Footer>
              <Button variant="secondary" onPress={onClose} data-testid="preview-close">
                Close
              </Button>
              <Button variant="primary" onPress={() => downloadFile(path)} data-testid="preview-download">
                <Download aria-hidden />
                Download
              </Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </Modal>
  );
}

/** Text body: fetched through the API client, cached per path. */
function TextPreviewBody({ path }: { path: string }) {
  const query = useQuery({
    queryKey: ['file-text', path],
    queryFn: () => getTextPreview(path),
  });

  if (query.isPending) {
    return (
      <div className="flex items-center gap-3 text-sm text-muted" data-testid="preview-loading">
        <Spinner size="sm" aria-label="Loading preview" />
        <span>Loading…</span>
      </div>
    );
  }
  if (query.isError) {
    return (
      <div role="alert" className="flex flex-col items-start gap-3">
        <p className="text-sm text-red-500">
          Could not load the preview:
          {query.error instanceof Error ? ` ${query.error.message}` : ` ${String(query.error)}`}
        </p>
        <Button variant="secondary" onPress={() => void query.refetch()}>
          Retry
        </Button>
      </div>
    );
  }
  const { content, truncated } = query.data;
  return (
    <div className="flex min-w-0 flex-col gap-2">
      {truncated && (
        <p
          className="flex items-center gap-2 rounded bg-warning-soft px-3 py-2 text-sm text-warning-soft-foreground"
          data-testid="preview-truncated"
        >
          <WarningCircle aria-hidden className="shrink-0" />
          Preview truncated at 512 KB — use Download for the whole file.
        </p>
      )}
      <pre
        className="max-h-[60vh] overflow-auto rounded bg-surface-secondary p-3 font-mono text-xs whitespace-pre-wrap break-all"
        data-testid="preview-text"
      >
        {content}
      </pre>
    </div>
  );
}

/**
 * Image body: the browser fetches /api/file itself via <img src>. That URL
 * is not a client-fetched path string, so it bypasses the client's
 * traversal-refusal fence by design — the server-side Sandbox remains the
 * authority for every byte it serves.
 */
function ImagePreview({ path, name }: { path: string; name: string }) {
  return (
    <div className="flex justify-center">
      <img
        src={fileUrl(path)}
        alt={name}
        className="max-h-[60vh] w-auto rounded"
        data-testid="preview-image"
      />
    </div>
  );
}
