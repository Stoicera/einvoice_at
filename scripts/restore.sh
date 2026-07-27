#!/usr/bin/env bash
#
# Restores an einvoice-at dump produced by scripts/backup.sh.
#
# Usage:
#   scripts/restore.sh DUMP_FILE [--force]
#
# Environment (all optional; defaults match docker-compose.yml / .env.example):
#   POSTGRES_HOST  POSTGRES_PORT  POSTGRES_DB  POSTGRES_USER  PGPASSWORD
#
# THIS SCRIPT DESTROYS THE TARGET DATABASE'S CONTENTS. `pg_restore --clean` drops every object it
# is about to recreate. It therefore refuses to run without either an interactive confirmation or an
# explicit --force, and it prints which database it is about to overwrite first — the failure mode
# worth engineering against here is a restore aimed at production while meaning staging.
#
# STOP THE APPLICATION FIRST. Flyway validates the schema at startup and the application holds
# connections; restoring underneath a running instance gives you a half-dropped schema and an
# application that believes otherwise. docs/deployment.md's drill does this in order.
set -euo pipefail

DUMP="${1:-}"
FORCE="${2:-}"

if [ -z "$DUMP" ]; then
  echo "usage: scripts/restore.sh DUMP_FILE [--force]" >&2
  exit 2
fi
if [ ! -r "$DUMP" ]; then
  echo "error: cannot read ${DUMP}" >&2
  exit 1
fi

POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="${POSTGRES_DB:-einvoice}"
POSTGRES_USER="${POSTGRES_USER:-einvoice}"

command -v pg_restore >/dev/null 2>&1 || {
  echo "error: pg_restore is not on PATH (install postgresql-client)" >&2
  exit 1
}

# Check the checksum when the backup wrote one. A restore is the moment you most want to know that
# the bytes are the bytes.
if [ -r "${DUMP}.sha256" ]; then
  echo "Verifying checksum"
  (cd "$(dirname "$DUMP")" && sha256sum --check --status "$(basename "$DUMP").sha256") || {
    echo "error: ${DUMP} does not match its recorded SHA-256 — refusing to restore" >&2
    exit 1
  }
fi

echo "Archive contents:"
pg_restore --list "$DUMP" | grep '^[0-9]' | sed 's/^/  /' | head -20
echo

echo "ABOUT TO OVERWRITE: ${POSTGRES_DB} on ${POSTGRES_HOST}:${POSTGRES_PORT} as ${POSTGRES_USER}"
if [ "$FORCE" != "--force" ]; then
  read -r -p "Type the database name to confirm: " CONFIRMED
  if [ "$CONFIRMED" != "$POSTGRES_DB" ]; then
    echo "aborted" >&2
    exit 1
  fi
fi

# --clean --if-exists: drop what is being replaced, and do not fail on the first run against an
# empty database. --no-owner/--no-privileges pairs with the dump's own flags so a restore into a
# differently-named role works, which is exactly the case a drill exercises.
#
# NOT --single-transaction: a partially applied restore is loud and recoverable, whereas a
# multi-gigabyte restore that rolls back at 99 % has to start over. --exit-on-error keeps it honest.
echo "Restoring"
pg_restore \
  --host="$POSTGRES_HOST" \
  --port="$POSTGRES_PORT" \
  --username="$POSTGRES_USER" \
  --dbname="$POSTGRES_DB" \
  --clean --if-exists \
  --no-owner --no-privileges \
  --exit-on-error \
  "$DUMP"

echo "Restored. Row counts:"
PSQL=(psql --host="$POSTGRES_HOST" --port="$POSTGRES_PORT" --username="$POSTGRES_USER"
      --dbname="$POSTGRES_DB" --tuples-only --no-align)
for table in tenant invoice report api_key audit_event; do
  count="$("${PSQL[@]}" --command="select count(*) from ${table}")"
  printf '  %-14s %s\n' "$table" "$count"
done

echo
echo "Now start the application. Flyway will validate the schema against its migrations;"
echo "a mismatch there means the dump predates a migration and needs a newer one."
