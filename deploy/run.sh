#!/usr/bin/env bash
# =============================================================================
#  Battery HD Pro — 服务器端部署 / 更新脚本
#  用法（在服务器上）：
#     cd /work/batteryhd/deploy && ./run.sh [up|build|migrate|seed|restart|stop|logs|status]
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")"
set -a; . ./.env; set +a

PORT="${HTTP_PORT:-8082}"
log()  { printf '\033[36m[deploy]\033[0m %s\n' "$*"; }

[ -f ./.env ] || { echo "缺少 deploy/.env，请先 cp .env.example .env 并修改密码"; exit 1; }

artisan() {
  docker compose run --rm --no-deps -T php php /var/www/api/artisan "$@"
}

wait_mysql() {
  log "等待 MySQL 就绪…"
  for _ in $(seq 1 60); do
    if docker compose exec -T mysql \
        mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent >/dev/null 2>&1; then
      log "MySQL 已就绪"
      return 0
    fi
    sleep 2
  done
  echo "MySQL 启动超时"; exit 1
}

do_migrate() {
  wait_mysql
  log "执行数据库迁移…"
  artisan migrate --force
  log "预建事件表分区…"
  artisan analytics:ensure-partitions --months=3
}

do_seed() {
  log "初始化广告配置（幂等）…"
  artisan db:seed --class=AdConfigSeeder --force || true
}

do_optimize() {
  log "刷新缓存…"
  artisan config:clear || true
  artisan route:clear  || true
  artisan view:clear   || true
  artisan config:cache || true
  artisan route:cache  || true
}

do_permissions() {
  log "修正目录权限…"
  docker compose run --rm --no-deps -T php \
    chown -R www-data:www-data /var/www/api/storage /var/www/api/bootstrap/cache || true
}

do_status() {
  docker compose ps
  echo
  log "健康检查："
  curl -sS -o /dev/null -w "  API    HTTP %{http_code}\n" "http://127.0.0.1:${PORT}/api/v1/health" || true
  curl -sS -o /dev/null -w "  配置   HTTP %{http_code}\n" "http://127.0.0.1:${PORT}/up" || true
}

case "${1:-up}" in
  up)
    log "构建并启动容器…"
    docker compose up -d --build
    do_migrate
    do_seed
    do_optimize
    do_permissions
    log "重启队列 / 调度器…"
    docker compose restart queue scheduler || true
    do_status
    ;;
  build)
    log "重建镜像…"
    docker compose build
    ;;
  migrate)
    do_migrate
    ;;
  seed)
    do_seed
    ;;
  optimize)
    do_optimize
    ;;
  restart)
    log "重启服务…"
    docker compose restart
    ;;
  stop)
    log "停止服务…"
    docker compose down
    ;;
  logs)
    docker compose logs -f --tail=200 ${2:-}
    ;;
  status)
    do_status
    ;;
  *)
    echo "用法: $0 [up|build|migrate|seed|optimize|restart|stop|logs|status]"; exit 1;;
esac
