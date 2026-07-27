#!/usr/bin/env bash
#
# Backs up the einvoice-at PostgreSQL database to a compressed custom-format dump.
#
# Custom format (-Fc), not plain SQL: it is compressed, it can be restored selectively, and
# pg_restore can read it in parallel. A plain-SQL dump of a database whose largest column is JSONB
# is several times the size for no benefit.
#
# Usage:
#   scripts/backup.sh [TARGET_DIR]
#
# Environment (all optional; defaults match docker-compose.yml / .env.example):
#   POSTGRES_HOST  POSTGRES_PORT  POSTGRES_DB  POSTGRES_USER  PGPASSWORD
#   BACKUP_KEEP_DAYS   delete dumps older than this (default 30; 0 disables pruning)
#
# Cron (docs/deployment.md installs exactly this line):
#   15 2 * * *  cd /opt/einvoice-at && PGPASSWORD=... scripts/backup.sh /var/backups/einvoice
#
# EXIT CODE IS THE POINT. pg_dump writing a truncated file and returning non-zero is a backup you
# know about; the same file with a zero exit is a backup you find out about during a restore. Every
# step below is checked, and the dump is verified by reading it back with pg_restore --list before
# the script reports success.
set -euo pipefail

TARGET_DIR="${1:-./backups}"

POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="${POSTGRES_DB:-einvoice}"
POSTGRES_USER="${POSTGRES_USER:-einvoice}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-30}"

command -v pg_dump >/dev/null 2>&1 || {
  echo "error: pg_dump is not on PATH (install postgresql-client)" >&2
  exit 1
}

mkdir -p "$TARGET_DIR"

# UTC and a sortable name, so `ls` is chronological and a server that changes timezone does not
# produce two dumps that look like the same minute.
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP="${TARGET_DIR}/einvoice-${STAMP}.dump"

echo "Dumping ${POSTGRES_DB}@${POSTGRES_HOST}:${POSTGRES_PORT} -> ${DUMP}"
pg_dump \
  --host="$POSTGRES_HOST" \
  --port="$POSTGRES_PORT" \
  --username="$POSTGRES_USER" \
  --dbname="$POSTGRES_DB" \
  --format=custom \
  --compress=9 \
  --no-owner \
  --no-privileges \
  --file="$DUMP"

# Verify the dump is readable before claiming success. pg_restore --list parses the whole archive's
# table of contents, so a truncated or corrupt file fails here rather than during the restore that
# someone attempts under pressure six months from now.
echo "Verifying archive"
ENTRIES="$(pg_restore --list "$DUMP" | grep -c '^[0-9]' || true)"
if [ "${ENTRIES:-0}" -lt 1 ]; then
  echo "error: ${DUMP} contains no restorable entries — refusing to call this a backup" >&2
  exit 1
fi

# A checksum beside the dump, so a later restore can prove it is reading the same bytes that were
# written. Cheap, and the alternative is trusting a filesystem you are restoring *because* you
# stopped trusting it.
sha256sum "$DUMP" > "${DUMP}.sha256"

SIZE="$(du -h "$DUMP" | cut -f1)"
echo "OK: ${DUMP} (${SIZE}, ${ENTRIES} entries)"

if [ "$BACKUP_KEEP_DAYS" -gt 0 ]; then
  echo "Pruning dumps older than ${BACKUP_KEEP_DAYS} days in ${TARGET_DIR}"
  find "$TARGET_DIR" -maxdepth 1 -name 'einvoice-*.dump' -type f \
    -mtime "+${BACKUP_KEEP_DAYS}" -print -delete
  find "$TARGET_DIR" -maxdepth 1 -name 'einvoice-*.dump.sha256' -type f \
    -mtime "+${BACKUP_KEEP_DAYS}" -delete
fi
