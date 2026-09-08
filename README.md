# fs-browser

Java web application (.war) that browses one configured directory (the Root)
over HTTP. Ships as a deployable WAR and as a container image.

## Container

The image is multi-stage: it compiles the WAR from source on
`maven:3.9-eclipse-temurin-25` and runs it on `eclipse-temurin:25-jre` as a
non-root user. Both base images publish `linux/amd64` and `linux/arm64`, so
the same build serves x86 servers and Raspberry Pis (the WAR is
architecture-neutral). Tests are skipped inside image builds; run
`./mvnw package` for the verifying build.

Build:

    podman build -t fs-browser .

Run, serving a host directory as the Root on http://localhost:8080:

    podman run -d --name fs-browser -p 8080:8080 \
      --mount type=bind,src=/path/to/your/data,dst=/data \
      fs-browser

- `-p 8080:8080` maps host port 8080 to the container's port 8080.
- `--mount type=bind,src=...,dst=/data` bind-mounts the directory to browse.
  The image defaults `FSB_ROOT=/data`, so a mount at `/data` needs no extra
  flags; mount elsewhere and set `-e FSB_ROOT=/elsewhere` to match.

Environment variables (both overridable with `-e`):

- `FSB_ROOT` — the directory the app browses. Default in the image: `/data`.
  The app refuses to start if the directory does not exist.
- `FSB_MODE` — `read-only` (default) or `read-write`. In read-only mode the
  UI hides write actions and write endpoints answer 403.

Liveness check: `curl http://localhost:8080/api/ping` answers with the
configured Root, e.g. `{"pong":true,"root":"/data"}`.

## Modes and writes

fs-browser ships read-only: it browses the Root, nothing more. Setting
`FSB_MODE=read-write` (environment variable, or the `FSB_MODE` Spring
property) opts the instance into writes — currently three:

- `POST /api/upload?path=<target dir>` — multipart/form-data with a `file`
  part. The part's filename must be a plain name (path-shaped, empty,
  dot/dot-dot and null-byte names are rejected with 400 — no silent
  basenaming); an existing Entry of the same name is never overwritten
  (409); the target directory goes through the same Sandbox resolution as
  every other path (traversal → 400, missing → 404). Success answers 201
  with `{"name": …, "size": …}` of the stored Entry. One upload may not
  exceed 100 MB (`spring.servlet.multipart.max-file-size` in
  `src/main/resources/application.properties`; over-limit requests are
  refused with 413 before anything is written).

- `POST /api/rename` — JSON body
  `{"path": "<parent dir>", "from": "<old name>", "to": "<new name>"}`.
  Renames one Entry — a file or a whole directory (children move with it).
  The `to` name passes the same EntryStore rules as an uploaded filename
  (pathy/empty/dot/null-byte → 400); `from` must exist inside the parent
  (404); an existing `to` is never overwritten (409, including renaming an
  Entry onto itself); the parent goes through Sandbox resolution
  (traversal → 400, missing → 404); missing or blank `from`/`to` fields →
  400. The move uses `Files.move` with `ATOMIC_MOVE` where the filesystem
  supports it, falling back to a plain (still non-overwriting) move where
  it does not. Success answers 200 with `{"name": "<new name>"}`.

- `DELETE /api/file?path=<dir>&name=<entry>` — deletes one Entry. Files
  delete directly; **deleting a directory removes its contents too**
  (depth-first walk, then the directory itself) — 204, empty body. The
  client's confirmation dialog is the safety gate: it names the Entry and
  states that a directory's contents go with it. Same rules otherwise:
  `name` passes the EntryStore shape checks (400), missing Entry → 404,
  missing or blank `name` → 400, traversal parent → 400.

In read-only mode every write endpoint answers `403 {"error": …}` before
looking at paths or bodies, and the SPA renders no write UI at all (no
disabled buttons — the upload control and the per-entry rename/delete
menu simply do not exist). The SPA discovers the mode once per load via
`GET /api/mode` → `{"mode":"read-only"}` or `{"mode":"read-write"}`.

Any other `FSB_MODE` value fails startup with a message naming the two
allowed spellings — a typo never degrades into an accidentally writable
instance.
