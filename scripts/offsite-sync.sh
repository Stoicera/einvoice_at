#!/usr/bin/env bash
#
# Copies the local PostgreSQL dumps produced by scripts/backup.sh to off-site storage.
#
# WHY THIS SCRIPT EXISTS. scripts/backup.sh writes its dump to a directory on the production
# server — the same physical disk that holds the database it just dumped. That is a *copy*, not a
# backup: the single most likely event it must survive (that disk, that server, or that provider
# account going away) destroys both halves at once. This script is the step that turns the copy
# into a backup.
#
# Usage:
#   scripts/offsite-sync.sh [SOURCE_DIR]
#
# Environment (read from a root-only 0600 env file in production; see docs/deployment.md §10.4):
#   OFFSITE_ENV_FILE   config file sourced if present (default /opt/einvoice-at/offsite.env).
#                      This is how cron, which has almost no environment, gets the settings below.
#   OFFSITE_TARGET     rsync destination, e.g. u123456@u123456.your-storagebox.de:einvoice/
#                      Unset => the script reports "not configured" and exits 0 (see below).
#   OFFSITE_SSH_KEY    private key path (default /root/.ssh/storagebox)
#   OFFSITE_SSH_PORT   SSH port (default 23 — Hetzner Storage Box, not 22)
#   OFFSITE_REQUIRED   set to 1 once off-site storage is armed; makes a missing OFFSITE_TARGET a
#                      hard error instead of a clean skip.
#   OFFSITE_VERIFY     set to 0 to skip the read-back verification (default 1; see VERIFICATION).
#
# Cron: chained after the dump so it can only run on a dump that succeeded — docs/deployment.md §10.4
# installs the exact line.
#
# TWO DELIBERATE DEPARTURES from the obvious `rsync -a --delete` one-liner, both of which exist
# because the obvious version can destroy the thing it is protecting:
#
#   1. NO --delete. With --delete, rsync makes the remote mirror the local directory — so a local
#      directory that has been emptied (failed disk, wrong mount, a typo in BACKUP_KEEP_DAYS, a
#      restore gone wrong) propagates the emptiness off-site on the next nightly run, deleting the
#      only surviving copy at exactly the moment it is needed. Dumps are ~16 KB and a Storage Box
#      BX11 is 1 TB; there is no capacity argument that justifies taking that risk. Off-site
#      retention is therefore "grows forever", pruned by hand if it ever matters.
#
#   2. A GUARD ON AN EMPTY SOURCE. Even without --delete, a source directory containing no dumps
#      means something is already wrong upstream. Syncing nothing and reporting success would hide
#      it for as long as nobody looks. The script fails loudly instead.
#
# VERIFICATION. rsync exiting 0 proves bytes were sent, not that they can be read back. The check
# that matters is the one a restore actually performs, so the script downloads the newest dump it
# just uploaded into a temporary file and compares its SHA-256 against the sidecar backup.sh wrote.
# That catches a truncated upload, a wrong remote path, and a remote that silently accepts writes
# it cannot serve.
set -euo pipefail

SOURCE_DIR="${1:-/var/backups/einvoice}"

# Cron runs with a near-empty environment, so the configuration cannot be assumed to be exported
# into it. Sourcing the env file here — rather than teaching the crontab line to do it — keeps the
# cron entry to one readable command and means arming off-site storage later is a single new file
# and no edit to anything already running.
OFFSITE_ENV_FILE="${OFFSITE_ENV_FILE:-/opt/einvoice-at/offsite.env}"
if [ -f "$OFFSITE_ENV_FILE" ]; then
  # set -a exports everything the file assigns, so plain KEY=value lines behave as the operator
  # expects without an `export` on each one.
  set -a
  # shellcheck disable=SC1090  # path is configuration, not a literal known at authoring time
  . "$OFFSITE_ENV_FILE"
  set +a
fi

OFFSITE_SSH_KEY="${OFFSITE_SSH_KEY:-/root/.ssh/storagebox}"
OFFSITE_SSH_PORT="${OFFSITE_SSH_PORT:-23}"
OFFSITE_REQUIRED="${OFFSITE_REQUIRED:-0}"
OFFSITE_VERIFY="${OFFSITE_VERIFY:-1}"

# Not configured yet is a legitimate state: this script ships and is installed before the storage
# account exists, so that arming it later is one env file and no code change. It must never be a
# silent state, though — an unset target and a typo'd target would otherwise look identical, and
# the typo would go unnoticed until a restore. Once storage is armed, OFFSITE_REQUIRED=1 turns the
# skip into a failure, which is what makes the nightly log trustworthy from then on.
if [ -z "${OFFSITE_TARGET:-}" ]; then
  if [ "$OFFSITE_REQUIRED" = "1" ]; then
    echo "error: OFFSITE_REQUIRED=1 but OFFSITE_TARGET is unset — off-site copy did NOT run" >&2
    exit 1
  fi
  echo "OFF-SITE SYNC NOT CONFIGURED: OFFSITE_TARGET is unset, so the dumps in ${SOURCE_DIR} exist"
  echo "  on one disk only. See docs/deployment.md §10.4 to arm it."
  exit 0
fi

for tool in rsync ssh sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "error: ${tool} is not on PATH" >&2
    exit 1
  }
done

[ -d "$SOURCE_DIR" ] || {
  echo "error: source directory ${SOURCE_DIR} does not exist" >&2
  exit 1
}

[ -r "$OFFSITE_SSH_KEY" ] || {
  echo "error: SSH key ${OFFSITE_SSH_KEY} is missing or unreadable" >&2
  exit 1
}

# See departure 2 above. `find -print -quit` stops at the first hit rather than listing a directory
# that may hold years of dumps.
if [ -z "$(find "$SOURCE_DIR" -maxdepth 1 -name 'einvoice-*.dump' -type f -print -quit)" ]; then
  echo "error: ${SOURCE_DIR} contains no einvoice-*.dump files — refusing to sync an empty source" >&2
  echo "  (an empty backup directory is an upstream failure, not something to mirror off-site)" >&2
  exit 1
fi

SSH_CMD="ssh -i ${OFFSITE_SSH_KEY} -p ${OFFSITE_SSH_PORT} -o BatchMode=yes -o StrictHostKeyChecking=accept-new"

echo "Syncing ${SOURCE_DIR}/ -> ${OFFSITE_TARGET}"
# --archive preserves mtimes, which the local pruning logic and any human reading `ls` both rely on.
# --partial is deliberately absent: a half-transferred dump left in place under its final name is
# indistinguishable from a good one. rsync's default (write to a temporary name, rename on
# completion) is the atomic behaviour we want.
rsync --archive --human-readable --stats \
  -e "$SSH_CMD" \
  --include='einvoice-*.dump' \
  --include='einvoice-*.dump.sha256' \
  --exclude='*' \
  "${SOURCE_DIR}/" "${OFFSITE_TARGET}"

if [ "$OFFSITE_VERIFY" != "1" ]; then
  echo "OK: sync completed (read-back verification disabled by OFFSITE_VERIFY=0)"
  exit 0
fi

# Verify by reading back, not by trusting the exit code. The newest dump is the one a restore would
# reach for, so it is the one worth proving.
NEWEST="$(find "$SOURCE_DIR" -maxdepth 1 -name 'einvoice-*.dump' -type f -printf '%T@ %p\n' \
  | sort -rn | head -1 | cut -d' ' -f2-)"
NEWEST_NAME="$(basename "$NEWEST")"

TMP_DIR="$(mktemp -d)"
# shellcheck disable=SC2064  # TMP_DIR is expanded now on purpose: the trap must clean up this path
# even if the variable is later reassigned.
trap "rm -rf '${TMP_DIR}'" EXIT

echo "Verifying ${NEWEST_NAME} by reading it back from off-site storage"
rsync --archive -e "$SSH_CMD" \
  "${OFFSITE_TARGET%/}/${NEWEST_NAME}" "${TMP_DIR}/${NEWEST_NAME}"

EXPECTED="$(cut -d' ' -f1 < "${NEWEST}.sha256")"
ACTUAL="$(sha256sum "${TMP_DIR}/${NEWEST_NAME}" | cut -d' ' -f1)"

if [ "$EXPECTED" != "$ACTUAL" ]; then
  echo "error: off-site copy of ${NEWEST_NAME} does not match the local dump" >&2
  echo "  expected ${EXPECTED}" >&2
  echo "  actual   ${ACTUAL}" >&2
  exit 1
fi

REMOTE_COUNT="$($SSH_CMD "${OFFSITE_TARGET%%:*}" "ls ${OFFSITE_TARGET#*:} 2>/dev/null | grep -c '\.dump$'" 2>/dev/null || echo '?')"
echo "OK: ${NEWEST_NAME} verified off-site (sha256 matches); ${REMOTE_COUNT} dump(s) now stored remotely"
