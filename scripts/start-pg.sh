#!/usr/bin/env bash
# 免 root 启动本地 PostgreSQL（端口 5439，库 energy / 用户 energy，trust）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEB_DIR="$ROOT/.downloads/apt/archives"
PREFIX="$ROOT/tools/pgsql"
PGDATA="$ROOT/data/pgdata"
PGPORT="${PGPORT:-5439}"
BIN="$PREFIX/usr/lib/postgresql/15/bin"

if [ ! -x "$BIN/initdb" ]; then
  echo "[pg] 解包 PostgreSQL debs -> $PREFIX"
  mkdir -p "$PREFIX"
  for deb in "$DEB_DIR"/*.deb; do
    echo "  dpkg-deb -x $(basename "$deb")"
    dpkg-deb -x "$deb" "$PREFIX"
  done
  # 运行期共享库集中到 $PREFIX/lib（Debian 多架构路径 + PG 私有库）
  mkdir -p "$PREFIX/lib"
  cp -a "$PREFIX/usr/lib/aarch64-linux-gnu/." "$PREFIX/lib/" 2>/dev/null || true
  cp -a "$PREFIX/usr/lib/postgresql/15/lib/." "$PREFIX/lib/" 2>/dev/null || true
fi

export LD_LIBRARY_PATH="$PREFIX/lib:${LD_LIBRARY_PATH:-}"
export PATH="$BIN:$PATH"

if [ ! -d "$PGDATA" ]; then
  echo "[pg] initdb -> $PGDATA"
  mkdir -p "$PGDATA"
  "$BIN/initdb" -D "$PGDATA" -U energy --auth=trust --encoding=UTF8 >/dev/null
  {
    echo "port = $PGPORT"
    echo "listen_addresses = '127.0.0.1'"
    echo "unix_socket_directories = '/tmp'"
    echo "timezone = 'Asia/Shanghai'"
    echo "log_timezone = 'Asia/Shanghai'"
  } >> "$PGDATA/postgresql.conf"
fi

if ! "$BIN/pg_ctl" -D "$PGDATA" status >/dev/null 2>&1; then
  echo "[pg] 启动 postgres (port $PGPORT)"
  "$BIN/pg_ctl" -D "$PGDATA" -l "$ROOT/data/pg.log" -w start
fi

if ! "$BIN/psql" -h 127.0.0.1 -p "$PGPORT" -U energy -tAc "SELECT 1 FROM pg_database WHERE datname='energy'" | grep -q 1; then
  echo "[pg] 创建数据库 energy"
  "$BIN/createdb" -h 127.0.0.1 -p "$PGPORT" -U energy energy
fi

echo "[pg] 就绪: postgresql://energy@127.0.0.1:$PGPORT/energy"
