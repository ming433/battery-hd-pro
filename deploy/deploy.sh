#!/usr/bin/env bash
# =============================================================================
#  Battery HD Pro — 本地一键构建 & 上传脚本
#  用法： ./deploy.sh
#  流程： 安装/更新 Composer 依赖 → rsync 到服务器 → 远程执行 deploy/run.sh up
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/deploy"

set -a; . ./.env; set +a

HOST="${DEPLOY_HOST}"
USER="${DEPLOY_USER:-root}"
KEY="${DEPLOY_SSH_KEY/#\~/$HOME}"
DIR="${DEPLOY_DIR:-/work/batteryhd}"
PORT="${HTTP_PORT:-8082}"

# Composer：优先使用环境变量指定的 phar，其次系统 composer
if [ -n "${COMPOSER_BIN:-}" ]; then
  COMPOSER="${COMPOSER_BIN}"
elif command -v composer >/dev/null 2>&1; then
  COMPOSER="composer"
else
  COMPOSER="/opt/homebrew/bin/php /tmp/composer.phar"
fi

log() { printf '\033[36m[local]\033[0m %s\n' "$*"; }

# ---------------------------------------------------------------- 后端依赖
log "同步 Composer 依赖（生产）…"
( cd "$ROOT/backend" && $COMPOSER install --no-dev --no-interaction --optimize-autoloader --no-scripts )

# ---------------------------------------------------------------- 上传到服务器
SSH_OPTS="-i $KEY -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes -o ConnectTimeout=20"
SSH="ssh $SSH_OPTS"
export RSYNC_RSH="ssh $SSH_OPTS"
RSYNC="rsync -az --delete"

log "创建远程目录 $DIR …"
$SSH "$USER@$HOST" "mkdir -p $DIR/backend $DIR/deploy"

log "上传后端源码 …"
$RSYNC \
  --exclude 'node_modules' --exclude '.git' --exclude '.env' \
  --exclude 'bootstrap/cache/*' \
  --exclude 'storage/logs/*' --exclude 'storage/framework/cache/*' \
  --exclude 'storage/framework/sessions/*' --exclude 'storage/framework/views/*' \
  "$ROOT/backend/" "$USER@$HOST:$DIR/backend/"

log "上传部署配置 …"
$RSYNC "$ROOT/deploy/" "$USER@$HOST:$DIR/deploy/"

# 后端 .env 仅在缺失时写入，避免覆盖已存在的 APP_KEY
log "检查远程 backend/.env …"
$SSH "$USER@$HOST" "test -f $DIR/backend/.env || cp $DIR/deploy/api.env $DIR/backend/.env; echo ok"

# ---------------------------------------------------------------- 远程启动
log "远程执行 run.sh up …"
$SSH "$USER@$HOST" "cd $DIR/deploy && chmod +x run.sh && ./run.sh up"

log "完成 → http://$HOST:$PORT/api/v1/health"
