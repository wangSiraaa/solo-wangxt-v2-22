#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for name in backend frontend; do
  if [ -f "$ROOT/data/$name.pid" ]; then
    pid=$(cat "$ROOT/data/$name.pid")
    kill "$pid" 2>/dev/null && echo "已停止 $name ($pid)" || true
    rm -f "$ROOT/data/$name.pid"
  fi
done
PREFIX="$ROOT/tools/pgsql"
BIN="$PREFIX/usr/lib/postgresql/15/bin"
if [ -x "$BIN/pg_ctl" ]; then
  export LD_LIBRARY_PATH="$PREFIX/lib:${LD_LIBRARY_PATH:-}"
  "$BIN/pg_ctl" -D "$ROOT/data/pgdata" stop >/dev/null 2>&1 && echo "已停止 PostgreSQL" || true
fi
