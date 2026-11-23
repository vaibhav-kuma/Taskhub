#!/usr/bin/env bash
# Restore a TaskHub database from a pg_dump custom-format archive.
#
#   TASKHUB_DB_URL="jdbc:postgresql://host:5432/taskhub" \
#   TASKHUB_DB_USER=taskhub TASKHUB_DB_PASSWORD=secret \
#   ./scripts/restore.sh backups/taskhub-20260101T021500Z.dump
#
# DESTRUCTIVE: drops and recreates every object in the target database.
# Stop the API before restoring, and take a fresh backup of the current state first.
set -euo pipefail

FILE="${1:?usage: restore.sh <dump-file>}"
: "${TASKHUB_DB_URL:?set TASKHUB_DB_URL}"
: "${TASKHUB_DB_USER:?set TASKHUB_DB_USER}"
: "${TASKHUB_DB_PASSWORD:?set TASKHUB_DB_PASSWORD}"

URL="${TASKHUB_DB_URL#jdbc:postgresql://}"
URL="${URL%%\?*}"
HOSTPORT="${URL%%/*}"
DBNAME="${URL#*/}"
HOST="${HOSTPORT%%:*}"
PORT="${HOSTPORT##*:}"
[[ "$PORT" == "$HOST" ]] && PORT=5432

echo "About to restore $FILE into $DBNAME on $HOST:$PORT"
read -r -p "Type the database name to confirm: " CONFIRM
[[ "$CONFIRM" == "$DBNAME" ]] || { echo "Aborted"; exit 1; }

PGPASSWORD="$TASKHUB_DB_PASSWORD" pg_restore \
  --host="$HOST" --port="$PORT" --username="$TASKHUB_DB_USER" --dbname="$DBNAME" \
  --clean --if-exists --no-owner --no-privileges --single-transaction \
  "$FILE"

echo "Restore complete. Start the API and run scripts/smoke-test.sh to verify."
