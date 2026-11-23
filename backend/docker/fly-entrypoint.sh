#!/bin/sh
# Supervises PostgreSQL 16 and the TaskHub API inside a single Fly Machine.
#
# - Postgres data lives on the mounted volume ($PGDATA, default /data/pgdata)
# - Postgres listens on loopback ONLY (never reachable from 6PN or the public internet)
# - The JVM runs as the unprivileged "taskhub" user
# - SIGTERM is forwarded so the API drains (server.shutdown=graceful)
set -eu

: "${POSTGRES_DB:=taskhub}"
: "${POSTGRES_USER:=taskhub}"
: "${PGDATA:=/data/pgdata}"
: "${JAVA_OPTS:=-XX:MaxRAMPercentage=55}"

if [ -z "${POSTGRES_PASSWORD:-}" ] && [ -z "${TASKHUB_DB_PASSWORD:-}" ]; then
    echo "[fly] POSTGRES_PASSWORD (or TASKHUB_DB_PASSWORD) must be set" >&2
    exit 1
fi
PG_PASSWORD="${POSTGRES_PASSWORD:-${TASKHUB_DB_PASSWORD}}"

mkdir -p "$PGDATA" /var/run/postgresql
# The volume is mounted as root; postgres (uid 70) must own its data directory.
chown -R postgres:postgres "$PGDATA" /var/run/postgresql

if [ ! -s "$PGDATA/PG_VERSION" ]; then
    echo "[fly] initialising a new PostgreSQL cluster in $PGDATA"
    printf '%s' "$PG_PASSWORD" > /tmp/pgpass
    chown postgres:postgres /tmp/pgpass
    su-exec postgres initdb \
        -D "$PGDATA" \
        -U "$POSTGRES_USER" \
        --pwfile=/tmp/pgpass \
        --auth-local=trust \
        --auth-host=scram-sha-256 \
        -E UTF8 >/tmp/initdb.log 2>&1 || { cat /tmp/initdb.log >&2; exit 1; }
    rm -f /tmp/pgpass
fi

echo "[fly] starting PostgreSQL"
su-exec postgres postgres \
    -D "$PGDATA" \
    -c listen_addresses=127.0.0.1 \
    -c unix_socket_directories=/var/run/postgresql \
    -c max_connections=60 \
    -c shared_buffers=192MB \
    -c log_min_messages=warning &
PG_PID=$!

# Stop the database if the API exits unexpectedly, so Fly restarts a consistent unit.
cleanup() {
    echo "[fly] shutting down"
    kill -TERM "$PG_PID" 2>/dev/null || true
    wait "$PG_PID" 2>/dev/null || true
}
trap cleanup TERM INT

READY=0
for _ in $(seq 1 60); do
    if su-exec postgres pg_isready -q -h 127.0.0.1 -p 5432; then READY=1; break; fi
    if ! kill -0 "$PG_PID" 2>/dev/null; then break; fi
    sleep 1
done
if [ "$READY" != "1" ]; then
    echo "[fly] PostgreSQL did not become ready" >&2
    exit 1
fi

# Create the application database on first boot (initdb only creates the maintenance DB).
if ! su-exec postgres psql -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres -tAc \
        "SELECT 1 FROM pg_database WHERE datname='$POSTGRES_DB'" 2>/dev/null | grep -q 1; then
    echo "[fly] creating database $POSTGRES_DB"
    su-exec postgres createdb -h 127.0.0.1 -U "$POSTGRES_USER" "$POSTGRES_DB"
fi

echo "[fly] starting TaskHub API"
su-exec taskhub java $JAVA_OPTS -jar /app/app.jar &
APP_PID=$!

wait "$APP_PID"
STATUS=$?
cleanup
exit "$STATUS"
