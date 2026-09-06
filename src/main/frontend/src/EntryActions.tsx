import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useForm } from '@tanstack/react-form';
import { z } from 'zod';
import {
  Alert,
  AlertDialog,
  Button,
  Dropdown,
  FieldError,
  Input,
  Label,
  Modal,
  TextField,
} from '@heroui/react';
import { DotsThree, PencilSimple, Trash } from '@phosphor-icons/react';
import { deleteEntry, renameEntry, type Entry } from './api';

/**
 * The per-row write affordances: a dropdown (Rename / Delete) that only
 * exists in read-write mode — BrowsePage's decision, so in read-only mode
 * the rows carry no actions at all, never disabled ones. Rename opens a
 * modal whose name field is validated inline by the Zod schema below (the
 * client mirror of the EntryStore's naming rules) before any submit;
 * delete opens an explicit AlertDialog naming the Entry. Both invalidate
 * ['tree', path] on success so the listing reflects the change, and
 * surface server failures (409/400/404/403) as the same Alert pattern the
 * upload control uses.
 */
export default function EntryActions({ entry, path }: { entry: Entry; path: string }) {
  const [renaming, setRenaming] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  return (
    <div className="flex shrink-0 items-center" data-testid={`entry-actions-${entry.name}`}>
      <Dropdown>
        <Dropdown.Trigger>
          <Button
            variant="secondary"
            aria-label={`Actions for ${entry.name}`}
            data-testid={`entry-menu-${entry.name}`}
          >
            <DotsThree aria-hidden />
          </Button>
        </Dropdown.Trigger>
        <Dropdown.Popover>
          <Dropdown.Menu
            aria-label={`Actions for ${entry.name}`}
            onAction={(key) => {
              if (key === 'rename') {
                setRenaming(true);
              } else if (key === 'delete') {
                setConfirmingDelete(true);
              }
            }}
          >
            <Dropdown.Item id="rename" textValue="Rename">
              <Label>
                <PencilSimple aria-hidden />
                Rename
              </Label>
            </Dropdown.Item>
            <Dropdown.Item id="delete" textValue="Delete" variant="danger">
              <Label>
                <Trash aria-hidden />
                Delete
              </Label>
            </Dropdown.Item>
          </Dropdown.Menu>
        </Dropdown.Popover>
      </Dropdown>

      {renaming && <RenameDialog entry={entry} path={path} onClose={() => setRenaming(false)} />}
      {confirmingDelete && (
        <DeleteDialog entry={entry} path={path} onClose={() => setConfirmingDelete(false)} />
      )}
    </div>
  );
}

/**
 * The single source of client-side truth for a new Entry name: the mirror
 * of the backend EntryStore's rules (plain name, no separators, no null
 * byte, no dot/dot-dot) plus a sane length ceiling. Zod 4 implements
 * Standard Schema natively, so the schema wires straight into TanStack
 * Form's field validators — no adapter package — and runs on every
 * change, before a submit could ever fire.
 */
export const entryNameSchema = z
  .string()
  .trim()
  .min(1, 'A name is required.')
  .max(255, 'Names are at most 255 characters long.')
  .refine((value) => !value.includes('/'), 'A name must not contain "/".')
  .refine((value) => !value.includes('\\'), 'A name must not contain "\\".')
  .refine((value) => !value.includes('\0'), 'A name must not contain null bytes.')
  .refine((value) => value !== '.' && value !== '..', 'That is not a valid name.');

/**
 * TanStack Form leaves the error shape to the validator; Zod's Standard
 * Schema issues are {message: …}. This pulls the first human sentence out.
 */
function firstIssue(error: unknown): string | null {
  if (error == null) {
    return null;
  }
  if (typeof error === 'string') {
    return error;
  }
  if (error instanceof Error) {
    return error.message;
  }
  if (typeof error === 'object' && 'message' in error) {
    const message = (error as { message?: unknown }).message;
    return message == null ? null : String(message);
  }
  return String(error);
}

/** Rename modal: TanStack Form + Zod inline validation, submit calls the api client. */
function RenameDialog({ entry, path, onClose }: { entry: Entry; path: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const form = useForm({
    defaultValues: { name: entry.name },
    onSubmit: async ({ value }) => {
      setError(null);
      // Belt and braces: the same schema that guided the UI gates the
      // submit too, so no bad name can reach the api client from here.
      const parsed = entryNameSchema.safeParse(value.name);
      if (!parsed.success) {
        return;
      }
      try {
        await renameEntry(path, entry.name, parsed.data);
        await queryClient.invalidateQueries({ queryKey: ['tree', path] });
        onClose();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
  });

  return (
    <Modal>
      <Modal.Backdrop isOpen onOpenChange={(open) => !open && onClose()}>
        <Modal.Container size="sm">
          <Modal.Dialog aria-label={`Rename ${entry.name}`} data-testid="rename-dialog">
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading className="truncate" data-testid="rename-title">
                Rename {entry.name}
              </Modal.Heading>
            </Modal.Header>
            <form
              onSubmit={(event) => {
                event.preventDefault();
                void form.handleSubmit();
              }}
            >
              <Modal.Body>
                <form.Field name="name" validators={{ onChange: entryNameSchema }}>
                  {(field) => {
                    const issue = firstIssue(field.state.meta.errors[0]);
                    return (
                      <TextField
                        name="name"
                        isInvalid={Boolean(issue)}
                        autoComplete="off"
                        className="w-full"
                      >
                        <Label>{entry.kind === 'DIR' ? 'Directory name' : 'File name'}</Label>
                        <Input
                          value={field.state.value}
                          onChange={(event) => field.handleChange(event.target.value)}
                          onBlur={field.handleBlur}
                          data-testid="rename-input"
                        />
                        <FieldError data-testid="rename-field-error">
                          {issue ?? undefined}
                        </FieldError>
                      </TextField>
                    );
                  }}
                </form.Field>
                {error && (
                  <Alert status="danger" className="mt-2" data-testid="rename-error">
                    <Alert.Indicator />
                    <Alert.Content>
                      <Alert.Title>Rename failed</Alert.Title>
                      <Alert.Description>{error}</Alert.Description>
                    </Alert.Content>
                  </Alert>
                )}
              </Modal.Body>
              <Modal.Footer>
                <Button variant="secondary" onPress={onClose} data-testid="rename-cancel">
                  Cancel
                </Button>
                {/*
                  Submit is gated on the live form state: TanStack Form's
                  canSubmit already folds in validation errors, and an
                  unchanged name would only earn a 409 from the server, so
                  it keeps the button quiet too.
                */}
                <form.Subscribe
                  selector={(state) =>
                    [state.canSubmit, state.isSubmitting, state.values.name] as const
                  }
                >
                  {([canSubmit, isSubmitting, name]) => (
                    <Button
                      variant="primary"
                      type="submit"
                      isDisabled={!canSubmit || isSubmitting || name.trim() === entry.name}
                      data-testid="rename-submit"
                    >
                      {isSubmitting ? 'Renaming…' : 'Rename'}
                    </Button>
                  )}
                </form.Subscribe>
              </Modal.Footer>
            </form>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </Modal>
  );
}

/** Delete confirmation: explicit, names the Entry, requires the confirm button. */
function DeleteDialog({ entry, path, onClose }: { entry: Entry; path: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function confirm() {
    setError(null);
    setBusy(true);
    try {
      await deleteEntry(path, entry.name);
      await queryClient.invalidateQueries({ queryKey: ['tree', path] });
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  }

  return (
    <AlertDialog>
      <AlertDialog.Backdrop isOpen onOpenChange={(open) => !open && onClose()}>
        <AlertDialog.Container size="sm">
          <AlertDialog.Dialog aria-label={`Delete ${entry.name}`} data-testid="delete-dialog">
            <AlertDialog.CloseTrigger />
            <AlertDialog.Header>
              <AlertDialog.Icon status="danger">
                <Trash aria-hidden />
              </AlertDialog.Icon>
              <AlertDialog.Heading data-testid="delete-title">
                Delete {entry.name}?
              </AlertDialog.Heading>
            </AlertDialog.Header>
            <AlertDialog.Body>
              <p data-testid="delete-description">
                This will permanently delete the {entry.kind === 'DIR' ? 'directory' : 'file'}{' '}
                <strong>{entry.name}</strong>
                {entry.kind === 'DIR' ? ' (only if it is empty)' : ''}. This action cannot be undone.
              </p>
              {error && (
                <Alert status="danger" className="mt-2" data-testid="delete-error">
                  <Alert.Indicator />
                  <Alert.Content>
                    <Alert.Title>Delete failed</Alert.Title>
                    <Alert.Description>{error}</Alert.Description>
                  </Alert.Content>
                </Alert>
              )}
            </AlertDialog.Body>
            <AlertDialog.Footer>
              <Button variant="secondary" onPress={onClose} data-testid="delete-cancel">
                Cancel
              </Button>
              <Button
                variant="danger"
                isDisabled={busy}
                onPress={() => void confirm()}
                data-testid="delete-confirm"
              >
                <Trash aria-hidden />
                {busy ? 'Deleting…' : 'Delete'}
              </Button>
            </AlertDialog.Footer>
          </AlertDialog.Dialog>
        </AlertDialog.Container>
      </AlertDialog.Backdrop>
    </AlertDialog>
  );
}
