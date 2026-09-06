import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Alert, Button } from '@heroui/react';
import { UploadSimple } from '@phosphor-icons/react';
import { uploadFile } from './api';

/**
 * The one write affordance: uploads a chosen file into the directory the
 * page is browsing. Rendered only when the instance runs read-write
 * (BrowsePage's decision — in read-only mode this component is absent from
 * the tree entirely, never merely disabled). On success the ['tree', path]
 * query is invalidated so the listing shows the new Entry; failures
 * surface as a HeroUI Alert with the server's detail (409 duplicate name,
 * 400 bad filename, …).
 */
export default function UploadControl({ path }: { path: string }) {
  const inputRef = useRef<HTMLInputElement>(null);
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function upload(file: File) {
    setError(null);
    setBusy(true);
    uploadFile(path, file)
      .then(() => void queryClient.invalidateQueries({ queryKey: ['tree', path] }))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setBusy(false));
  }

  return (
    <div className="flex flex-col gap-2" data-testid="upload-control">
      <input
        ref={inputRef}
        type="file"
        className="hidden"
        aria-hidden="true"
        tabIndex={-1}
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) {
            upload(file);
          }
          // Reset so picking the same file again re-fires onChange.
          event.target.value = '';
        }}
      />
      <Button
        variant="secondary"
        isDisabled={busy}
        data-testid="upload-button"
        onPress={() => inputRef.current?.click()}
      >
        <UploadSimple aria-hidden />
        {busy ? 'Uploading…' : 'Upload'}
      </Button>
      {error && (
        <Alert status="danger" data-testid="upload-error">
          <Alert.Indicator />
          <Alert.Content>
            <Alert.Title>Upload failed</Alert.Title>
            <Alert.Description>{error}</Alert.Description>
          </Alert.Content>
        </Alert>
      )}
    </div>
  );
}
