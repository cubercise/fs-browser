import { describe, expect, it } from 'vitest';
import { entryNameSchema } from './EntryActions';

// The rename dialog's client-side truth, tested at its own seam: the Zod
// mirror of the backend EntryStore's naming rules (plain name, no
// separators, no null byte, no dot/dot-dot) plus a length ceiling. Wired
// into TanStack Form via Zod 4's native Standard Schema implementation.
describe('entryNameSchema (rename validation)', () => {
  it('accepts plain names, unicode, and strips surrounding whitespace', () => {
    expect(entryNameSchema.safeParse('plain-name.txt').success).toBe(true);
    expect(entryNameSchema.safeParse('with space and ünicode.txt').success).toBe(true);
    expect(entryNameSchema.safeParse('  trimmed.txt  ').success).toBe(true);
    // Trimming is part of the parse: the parsed value is the clean name.
    expect(entryNameSchema.parse('  trimmed.txt  ')).toBe('trimmed.txt');
  });

  it('rejects empty, whitespace-only, dot, dot-dot, pathy, null-byte and over-long names', () => {
    for (const bad of [
      '',
      '   ',
      '.',
      '..',
      'a/b',
      'a\\b',
      '../escape',
      'nul\0byte',
      'x'.repeat(256),
    ]) {
      const result = entryNameSchema.safeParse(bad);
      expect(result.success, `expected rejection for ${JSON.stringify(bad)}`).toBe(false);
    }
  });

  it('explains itself: each rule yields a human message', () => {
    expect(entryNameSchema.safeParse('').error?.issues[0].message).toBe('A name is required.');
    expect(entryNameSchema.safeParse('a/b').error?.issues[0].message).toContain('must not contain');
    expect(entryNameSchema.safeParse('x'.repeat(256)).error?.issues[0].message).toContain('255');
  });
});
