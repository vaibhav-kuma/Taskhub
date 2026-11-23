#!/usr/bin/env bash
# Nightly logical backup of the TaskHub database.
#
#   TASKHUB_DB_URL="jdbc:postgresql://host:5432/taskhub?sslmode=require" \
#   TASKHUB_DB_USER=taskhub TASKHUB_DB_PASSWORD=secret \
#   BACKUP_DIR=/var/backups/taskhub RETENTION_DAYS=14 ./scripts/backup.sh
#
# Optional off-site copy (if the AWS CLI is installed and configured):
#   BACKUP_S3="s3://my-bucket/taskhub" ./scripts/backup.sh
#
# Suggested cron: 15 2 * * *  (02:15 daily)
set -euo pipefail

: "${TASKHUB_DB_URL:?set TASKHUB_DB_URL (jdbc:postgresql://host:port/dbname)}"
: "${TASKHUB_DB_USER:?set TASKHUB_DB_USER}"
: "${TASKHUB_DB_PASSWORD:?set TASKHUB_DB_PASSWORD}"

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

# jdbc:postgresql://host:5432/db?params -> host 5432 db
URL="${TASKHUB_DB_URL#jdbc:postgresql://}"
URL="${URL%%\?*}"
HOSTPORT="${URL%%/*}"
DBNAME="${URL#*/}"
HOST="${HOSTPORT%%:*}"
PORT="${HOSTPORT##*:}"
[[ "$PORT" == "$HOST" ]] && PORT=5432

mkdir -p "$BACKUP_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="$BACKUP_DIR/taskhub-$STAMP.dump"

echo "Backing up $DBNAME from $HOST:$PORT -> $FILE"
PGPASSWORD="$TASKHUB_DB_PASSWORD" pg_dump \
  --host="$HOST" --port="$PORT" --username="$TASKHUB_DB_USER" --dbname="$DBNAME" \
  --format=custom --compress=9 --no-owner --no-privileges \
  --file="$FILE"

pg_restore --list "$FILE" > /dev/null
echo "Backup verified ($(du -h "$FILE" | cut -f1))"

if [[ -n "${BACKUP_S3:-}" ]] && command -v aws >/dev/null; then
  aws s3 cp "$FILE" "$BACKUP_S3/" --only-show-errors
  echo "Uploaded to $BACKUP_S3/"
fi

find "$BACKUP_DIR" -name 'taskhub-*.dump' -mtime "+$RETENTION_DAYS" -print -delete
echo "Retention: kept the last $RETENTION_DAYS days"
