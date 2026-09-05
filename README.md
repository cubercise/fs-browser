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
