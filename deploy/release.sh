#!/usr/bin/env bash
# 一键发布 semgravio-app-registry（内网 only，issue #27）：打包 → scp → 服务器 infra 门禁
# → 解包 → compose up -d --build（不先 down）→ 容器内 actuator 健康验收（默认最长 180s，
# 超时/失败非零退出并提示日志入口）。仓库根执行：deploy/release.sh（PowerShell 里：bash deploy/release.sh）
#
# 前提：服务器应用目录已有生产 .env.production（首次自动准备 env 是 #28）；共享基础设施
# semgravio-{pg,redis,nginx} + webnet 由 semgravio-infra 负责，本脚本只做门禁检查、不修复不重建。
#
# 可覆盖变量（默认与 aiplatform 对齐）：
#   SERVER_HOST=3.70.172.160   SERVER_USER=ubuntu   SSH_KEY=$HOME/ssh/semgravio-eu-1.pem
#   REMOTE_DIR=semgravio-app-registry（相对服务器 $HOME；绝对路径以 / 开头也可）
#   INFRA_DIR=semgravio-infra（共享 infra 单元目录，排障提示用）
#   HEALTH_WAIT_SECONDS=180（健康验收上限）
# 常用开关：SKIP_BUILD=1 复用 dist/ 最新包只发布；ALLOW_DIRTY=1 带脏打包（明确警告）。
#
# SSH 安全：私钥 + BatchMode + ConnectTimeout，保留正常主机密钥校验——不用 sshpass、
# 不保存服务器密码、不设置 StrictHostKeyChecking=no。
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER_HOST="${SERVER_HOST:-3.70.172.160}"
SERVER_USER="${SERVER_USER:-ubuntu}"
SSH_KEY="${SSH_KEY:-$HOME/ssh/semgravio-eu-1.pem}"
REMOTE_DIR="${REMOTE_DIR:-semgravio-app-registry}"
INFRA_DIR="${INFRA_DIR:-semgravio-infra}"
REMOTE="${SERVER_USER}@${SERVER_HOST}"
SSH_OPTS=(-i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=15)

echo "== 0/4 前置检查（密钥 + 连通性） =="
[ -f "$SSH_KEY" ] || { echo "错误：找不到密钥 $SSH_KEY（可用 SSH_KEY=... 覆盖）"; exit 1; }
ssh "${SSH_OPTS[@]}" "$REMOTE" true

echo "== 1/4 打包 =="
if [ "${SKIP_BUILD:-0}" = "1" ]; then
  BUNDLE=$(ls -t dist/semgravio-app-registry-deploy-*.tar.gz 2>/dev/null | head -1 || true)
  [ -n "$BUNDLE" ] || { echo "错误：dist/ 没有现成包；去掉 SKIP_BUILD 全新打包，或先跑 deploy/build-bundle.sh"; exit 1; }
  echo "复用现有包：$BUNDLE"
else
  BUNDLE=$(deploy/build-bundle.sh | tee /dev/stderr | tail -n 1)
  [ -n "$BUNDLE" ] || exit 1
fi
BASENAME=$(basename "$BUNDLE")
[ -f "$BUNDLE" ] || { echo "错误：包文件不存在：$BUNDLE"; exit 1; }

echo "== 2/4 上传（$(du -h "$BUNDLE" | cut -f1) → $REMOTE） =="
scp -i "$SSH_KEY" "$BUNDLE" "$REMOTE:"

echo "== 3/4 服务器：infra 门禁 → env 门禁 → 解包 → compose up --build → 健康验收 =="
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "BUNDLE_FN=$BASENAME REMOTE_DIR=$REMOTE_DIR INFRA_DIR=$INFRA_DIR HEALTH_WAIT_SECONDS=${HEALTH_WAIT_SECONDS:-180} bash -s" <<'REMOTE'
set -euo pipefail
if [ "${REMOTE_DIR:0:1}" = "/" ]; then APP_DIR="$REMOTE_DIR"; else APP_DIR="$HOME/$REMOTE_DIR"; fi
if [ "${INFRA_DIR:0:1}" = "/" ]; then INFRA_PATH="$INFRA_DIR"; else INFRA_PATH="$HOME/$INFRA_DIR"; fi
mkdir -p "$APP_DIR"; cd "$APP_DIR"

echo "-- infra 前置健康检查（缺失/不健康即中止——先起共享基础设施：cd $INFRA_PATH && ./deploy.sh）"
infra_bad=0
for c in semgravio-pg semgravio-redis semgravio-nginx; do
  st=$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo missing)
  if [ "$st" != "healthy" ]; then
    echo "   ✗ $c：$st"
    infra_bad=1
  else
    echo "   ✓ $c healthy"
  fi
done
if ! docker network inspect webnet >/dev/null 2>&1; then
  echo "   ✗ 网络 webnet 缺失"
  infra_bad=1
fi
[ "$infra_bad" = 0 ] || { echo "infra 前置检查未过：先跑共享基础设施起栈（$INFRA_PATH/deploy.sh），再重跑发布"; exit 1; }

echo "-- 生产 env 门禁（本票前提：已有 env；首次自动生成是 #28；现网 env 永不被部署包覆盖）"
if [ ! -f .env.production ]; then
  echo "   ✗ 缺少 .env.production——先完成首次 env 准备再放行日常发布"
  exit 1
fi
grep -q '^REDIS_PASSWORD=.' .env.production \
  || { echo "   ✗ .env.production 缺 REDIS_PASSWORD（共享 semgravio-redis 已启用 requirepass）"; exit 1; }
grep -q '^APP_REGISTRY_API_SECRET_MASTER_KEY=.' .env.production \
  || { echo "   ✗ .env.production 缺 APP_REGISTRY_API_SECRET_MASTER_KEY（apiSecret AES-GCM 主密钥）"; exit 1; }

echo "-- 解包 $BUNDLE_FN（包内无 .env*，现网 env 不被覆盖）"
tar -xzf "$HOME/$BUNDLE_FN" --strip-components=1
rm "$HOME/$BUNDLE_FN"
echo "   REVISION: $(cat REVISION)"

echo "-- 构建并启动（compose up -d --build；不先 down 旧容器——重启策略/健康检查/日志轮转见 compose）"
docker compose -f docker-compose.prod.yml up -d --build

echo "-- 健康验收（容器内 actuator/health，最长 ${HEALTH_WAIT_SECONDS}s；期间 Flyway 在跑迁移属正常）"
i=0
until docker exec semgravio-app-registry curl -sf -o /dev/null http://localhost:8088/actuator/health; do
  i=$((i+2))
  [ "$i" -ge "$HEALTH_WAIT_SECONDS" ] && { echo "健康验收失败（${HEALTH_WAIT_SECONDS}s 未就绪）；查日志：docker logs semgravio-app-registry"; exit 1; }
  sleep 2
done
echo "   ✓ semgravio-app-registry healthy"
REMOTE

echo "== 4/4 完成：$BASENAME 已上线 =="
