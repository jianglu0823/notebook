#!/usr/bin/env bash
# init-milvus.sh — 在 nblm-milvus 容器内(或本地)运行 pymilvus 初始化
# 用法:
#   ./init-milvus.sh                       # 默认连 localhost:19530
#   MILVUS_HOST=nblm-milvus ./init-milvus.sh
# 退出码: 0 成功 / 非0 失败

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -z "${MILVUS_HOST:-}" && -f "${SCRIPT_DIR}/../../../../.env" ]]; then
    _env_file="${SCRIPT_DIR}/../../../../.env"
    MILVUS_HOST="$(grep -E '^MILVUS_HOST=' "$_env_file" | head -1 | cut -d= -f2- | tr -d '"' || true)"
fi
MILVUS_HOST="${MILVUS_HOST:-127.0.0.1}"
MILVUS_PORT="${MILVUS_PORT:-19530}"

echo "Target Milvus: ${MILVUS_HOST}:${MILVUS_PORT}"

# 优先在 milvus 容器内跑(自带 pymilvus 镜像);否则本地
if command -v docker >/dev/null 2>&1; then
    container="$(docker ps --format '{{.Names}}' | grep -E 'milvus' | head -1 || true)"
    if [[ -n "$container" ]]; then
        echo "Running inside container: $container"
        docker cp "${SCRIPT_DIR}/init-milvus.py" "$container:/tmp/init-milvus.py"
        docker exec -e MILVUS_HOST="$MILVUS_HOST" -e MILVUS_PORT="$MILVUS_PORT" \
            "$container" bash -c "pip install -q pymilvus 2>/dev/null || true; python3 /tmp/init-milvus.py"
        exit $?
    fi
fi

echo "Running locally (no milvus container detected) ..."
pip install -q pymilvus 2>/dev/null || true
MILVUS_HOST="$MILVUS_HOST" MILVUS_PORT="$MILVUS_PORT" python3 "${SCRIPT_DIR}/init-milvus.py"
