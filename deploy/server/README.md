# VocoCraft dedicated server in Docker

This deployment builds the dedicated Linux server from the current VocoCraft
fork and packages the checked-out `games/vococraft` submodule into the image.
Mutable world state stays on the host and is never baked into the image.

## Host layout

```text
/opt/vococraft-src/                 Git checkout
/opt/vococraft-server/config/       minetest.conf
/opt/vococraft-server/data/world/   persistent world
/opt/vococraft-server/backups/      world backups
```

The container runs as UID/GID `30000`, publishes UDP `30000`, uses a read-only
root filesystem, rotates Docker logs and has `restart: always`.

## Deploy

```bash
install -d -o 30000 -g 30000 /opt/vococraft-server/data
install -d /opt/vococraft-server/config /opt/vococraft-server/backups
cp deploy/server/minetest.conf.example \
  /opt/vococraft-server/config/minetest.conf
docker compose -f deploy/server/compose.yaml build
docker compose -f deploy/server/compose.yaml up -d
```

The default build uses Google's public mirror for the Alpine base image to
avoid anonymous Docker Hub rate limits. Set `VOCOCRAFT_DOCKER_IMAGE` to use a
different compatible Alpine 3.23 image.

For an existing server, copy the complete old world directory to
`/opt/vococraft-server/data/world` before the first start and set ownership to
`30000:30000`. Preserve `worldmods`, `auth.sqlite`, `players.sqlite`,
`mod_storage.sqlite` and `map.sqlite` together.

## Operations

```bash
docker compose -f deploy/server/compose.yaml ps
docker compose -f deploy/server/compose.yaml logs -f --tail=200
docker compose -f deploy/server/compose.yaml restart
docker inspect --format '{{.RestartCount}} {{.State.Health.Status}}' \
  vococraft-server
```

Stop the container before a filesystem-level SQLite backup:

```bash
docker compose -f deploy/server/compose.yaml stop
tar -C /opt/vococraft-server/data -czf \
  /opt/vococraft-server/backups/world-$(date -u +%Y%m%dT%H%M%SZ).tar.gz \
  world
docker compose -f deploy/server/compose.yaml start
```

The server should appear at:

```text
https://mydevstorya-servers-6285.twc1.net/list
```
