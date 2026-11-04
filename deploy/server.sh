#!/usr/bin/env bash
# 服务器运维小工具（issue #29；连接参数与 deploy/release.sh 同套默认、同法覆盖：
# SERVER_HOST/SERVER_USER/SSH_KEY/REMOTE_DIR；SSH 安全同 release——BatchMode+ConnectTimeout，
# 保留正常主机密钥校验。本脚本只在本地拼命令，docker 一律在服务器侧执行）：
#   deploy/server.sh status        容器概览（本服务 ＋ 共享 semgravio-{pg,redis,nginx} infra）
#   deploy/server.sh logs [svc]    跟日志，svc=app|pg|redis|nginx（缺省 app）
#   deploy/server.sh restart       只重启本服务容器（不重建镜像；不动共享 infra——
#                                  pg/redis/nginx 归 semgravio-infra 管）
#   deploy/server.sh dump          手工备份本服务独立库到服务器应用目录 backups/（与发布前
#                                  备份同一数据库边界与凭据；备份是恢复材料，不是自动回滚，
#                                  恢复需人工执行）
#   deploy/server.sh smoke         内部健康冒烟（容器内 actuator/health；不访问返回明文
#                                  apiSecret 的 bootstrap 端点）
#   deploy/server.sh sh            登服务器交互 shell
# 换版上线（打包＋发布＋发布前自动备份＋重建）不是这里，用 deploy/release.sh。
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER_HOST="${SERVER_HOST:-3.70.172.160}"
SERVER_USER="${SERVER_USER:-ubuntu}"
SSH_KEY="${SSH_KEY:-$HOME/ssh/semgravio-eu-1.pem}"
REMOTE_DIR="${REMOTE_DIR:-semgravio-app-registry}"
REMOTE="${SERVER_USER}@${SERVER_HOST}"
SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=15 "$REMOTE")

need_key() { [ -f "$SSH_KEY" ] || { echo "错误：找不到密钥 $SSH_KEY（可用 SSH_KEY=... 覆盖）"; exit 1; }; }

case "${1:-help}" in
  status)
    need_key
    "${SSH[@]}" "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}' | grep -E 'semgravio' || true"
    ;;
  logs)
    need_key
    case "${2:-app}" in
      app)   C=semgravio-app-registry ;;
      pg)    C=semgravio-pg ;;
      redis) C=semgravio-redis ;;
      nginx) C=semgravio-nginx ;;
      *)     C="$2" ;;
    esac
    exec "${SSH[@]}" "docker logs -f --tail 200 $C"
    ;;
  restart)
    need_key
    "${SSH[@]}" 'docker restart semgravio-app-registry >/dev/null && echo 已重启 semgravio-app-registry（不动共享 infra：pg/redis/nginx 归 semgravio-infra 管）'
    ;;
  dump)
    need_key
    "${SSH[@]}" "REMOTE_DIR=$REMOTE_DIR bash -s" <<'EOS'
set -euo pipefail
if [ "${REMOTE_DIR:0:1}" = "/" ]; then APP_DIR="$REMOTE_DIR"; else APP_DIR="$HOME/$REMOTE_DIR"; fi
cd "$APP_DIR" 2>/dev/null || { echo "错误：应用目录 $APP_DIR 不存在（先完成一次发布）" >&2; exit 1; }
[ -f .env.production ] || { echo "错误：缺 $APP_DIR/.env.production（先完成一次发布，#28 首次发布自动准备）" >&2; exit 1; }
set -a; . ./.env.production; set +a
mkdir -p backups
STAMP=$(date +%Y%m%d-%H%M%S)
TMP="backups/.semgravio_app_registry-$STAMP.sql.gz.tmp"
# 与 release.sh 发布前备份保持同一数据库边界：只 dump 本服务独立库 semgravio_app_registry，
# 用自己的低权限角色＋env 正本里的库密码（PGPASSWORD 只在服务器侧展开，不进命令行传输）；
# 不经手 PG 超管凭据。先写 .tmp 再验完整：失败绝不留下顶着正式名号的残缺备份。
if docker exec -e PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" semgravio-pg \
     pg_dump -U semgravio_app_registry -d semgravio_app_registry | ( umask 077; gzip > "$TMP" ) \
   && gzip -t "$TMP" \
   && gzip -cd "$TMP" | tail -n 5 | grep -q 'PostgreSQL database dump complete'; then
  chmod 600 "$TMP"
  FINAL="backups/semgravio_app_registry-$STAMP.sql.gz"
  n=1
  while [ -e "$FINAL" ]; do  # 同秒已有备份（如发布刚自动备份过）：不覆盖，追加序号
    FINAL="backups/semgravio_app_registry-$STAMP-$n.sql.gz"; n=$((n+1))
  done
  mv "$TMP" "$FINAL"
  ls -t backups/semgravio_app_registry-*.sql.gz | head -1
else
  rm -f "$TMP"
  echo "错误：备份失败（pg_dump 未完整产出，已清理残缺文件；详见上方 pg_dump 输出）" >&2
  exit 1
fi
EOS
    ;;
  smoke)
    need_key
    "${SSH[@]}" 'body=$(docker exec semgravio-app-registry curl -sf http://localhost:8088/actuator/health) || { echo "✗ 冒烟失败：容器内 actuator/health 不可达（docker logs semgravio-app-registry 查原因）" >&2; exit 1; }; echo "✓ actuator/health（容器内；不访问返回明文 apiSecret 的 bootstrap 端点）：$body"'
    ;;
  sh)
    need_key
    exec "${SSH[@]}"
    ;;
  *)
    grep '^# ' "$0" | tail -n +2
    ;;
esac
