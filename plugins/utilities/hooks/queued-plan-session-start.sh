#!/usr/bin/env bash
# SessionStart hook for /clear-and-implement.
# If a marker file at ~/.claude/.implement-on-next-start contains a plan path,
# inject an additionalContext payload telling the new session to read & execute it.
# Marker is consumed (deleted) on read. Markers older than 1 hour are discarded as stale.

set -u

MARKER="$HOME/.claude/.implement-on-next-start"
STALE_AFTER_SECONDS=3600

[ -f "$MARKER" ] || exit 0

# mtime in seconds since epoch — macOS uses `stat -f %m`, Linux uses `stat -c %Y`.
if mtime=$(stat -f %m "$MARKER" 2>/dev/null); then :; else mtime=$(stat -c %Y "$MARKER" 2>/dev/null || echo 0); fi
now=$(date +%s)
age=$(( now - mtime ))

if [ "$age" -gt "$STALE_AFTER_SECONDS" ]; then
  rm -f "$MARKER"
  exit 0
fi

# Read path, then consume the marker so it can't fire again.
plan_path=$(tr -d '\r\n' < "$MARKER")
rm -f "$MARKER"

# If the path is empty or the file is gone, silently no-op.
[ -n "$plan_path" ] && [ -f "$plan_path" ] || exit 0

# Build the JSON payload in Python — handles all path-escaping correctly.
PLAN_PATH="$plan_path" python3 <<'PY'
import json, os

path = os.environ["PLAN_PATH"]
context = (
    f"A plan was queued for implementation by /clear-and-implement. "
    f"Read the file at {path} now and execute it. "
    f"The user already approved this plan and cleared their context to give you a fresh start — "
    f"do not re-plan, do not re-ask for approval, just begin work."
)
print(json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "SessionStart",
        "additionalContext": context,
    }
}))
PY
