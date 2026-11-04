#!/usr/bin/env bash
# 一键发布 semgravio-app-registry（内网 only，issue #27 主链 + #28 首次发布自动准备 env）：
# 打包 → scp → 服务器 infra 门禁 → 生产 env 首次自动准备/全量校验 → 解包 → compose up -d --build
# （不先 down）→ 容器内 actuator 健康验收（默认最长 180s，超时/失败非零退出并提示日志入口）。
# 仓库根执行：deploy/release.sh（PowerShell 里：bash deploy/release.sh）
#
# 首次发布（服务器应用目录无 .env.production）：经共享 infra 的 ensure-db-user 契约幂等准备
# 独立库 semgravio_app_registry + 同名低权限角色（不经手 PG 超管密码）；从 infra 凭据正本取
# Redis 密码；自动生成数据库密码与 Base64 的 32 字节 AES master key；env 只在服务器创建、
# 权限 600、不进 Git/部署包/上传归档，三类 secret 一律不落终端输出。
# 重复发布：env 与全部凭据终身复用（master key 绝不重生成、不被部署包覆盖）；每次发布前
# 全量校验 env（必需变量非空、master key 为 Base64 且解码恰好 32 字节），失败在应用启动前
# 非零退出，且绝不自动换 master key“修复”。共享基础设施 semgravio-{pg,redis,nginx} + webnet
# 由 semgravio-infra 负责，本脚本只做门禁检查、不修复不重建。
#
# 可覆盖变量（默认与 aiplatform 对齐）：
#   SERVER_HOST=3.70.172.160   SERVER_USER=ubuntu   SSH_KEY=$HOME/ssh/semgravio-eu-1.pem
#   REMOTE_DIR=semgravio-app-registry（相对服务器 $HOME；绝对路径以 / 开头也可）
#   INFRA_DIR=semgravio-infra（共享 infra 单元目录：ensure-db-user 与凭据正本所在）
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
scp -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=15 "$BUNDLE" "$REMOTE:"

echo "== 3/4 服务器：infra 门禁 → env 首次准备/校验 → 解包 → compose up --build → 健康验收 =="
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "BUNDLE_FN=$BASENAME REMOTE_DIR=$REMOTE_DIR INFRA_DIR=$INFRA_DIR HEALTH_WAIT_SECONDS=${HEALTH_WAIT_SECONDS:-180} bash -s" <<'REMOTE'
set -euo pipefail
if [ "${REMOTE_DIR:0:1}" = "/" ]; then APP_DIR="$REMOTE_DIR"; else APP_DIR="$HOME/$REMOTE_DIR"; fi
if [ "${INFRA_DIR:0:1}" = "/" ]; then INFRA_PATH="$INFRA_DIR"; else INFRA_PATH="$HOME/$INFRA_DIR"; fi
mkdir -p "$APP_DIR"; cd "$APP_DIR"

DB_SERVICE=semgravio_app_registry   # 独立库＝低权限角色＝服务名（infra 建号 helper 口径）
ENV_FILE=.env.production

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

echo "-- 生产 env（#28：首次自动准备；每次发布全量校验；现网 env 永不被部署包覆盖）"
if [ ! -f "$ENV_FILE" ]; then
  echo "   首次发布：env 缺失，经共享 infra ensure-db-user 契约自动准备独立库+低权限角色（幂等，不经手 PG 超管密码）"
  [ -f "$INFRA_PATH/ensure-db-user.sh" ] \
    || { echo "   ✗ 缺 $INFRA_PATH/ensure-db-user.sh——共享 infra 不完整，先跑 $INFRA_PATH/deploy.sh"; exit 1; }
  [ -f "$INFRA_PATH/.env" ] \
    || { echo "   ✗ 缺 $INFRA_PATH/.env（共享 infra 凭据正本）——先跑 $INFRA_PATH/deploy.sh"; exit 1; }
  # Redis 密码只从 infra 凭据正本按行提取（不 source 正本——超管凭据绝不进发布进程）；
  # 值不落终端：只进即将 600 落盘的 env
  redis_pw=$(grep -E '^REDIS_PASSWORD=' "$INFRA_PATH/.env" | tail -n 1 | cut -d= -f2-)
  [ -n "$redis_pw" ] || { echo "   ✗ 共享 infra 凭据正本缺 REDIS_PASSWORD（$INFRA_PATH/.env）"; exit 1; }
  db_pw=$(openssl rand -hex 16)          # 应用库密码：本次首次发布生成，此后终身复用（存 env）
  master_key=$(openssl rand -base64 32)  # AES-256 主密钥：生成后终身不变（历史密文依赖它，#26 决策 10）
  # 建号 helper 的 stdout 是凭据分片（含库密码）——整体吞掉，任何情况下不进终端
  ( cd "$INFRA_PATH" && DB_PASSWORD="$db_pw" ./ensure-db-user.sh "$DB_SERVICE" >/dev/null ) \
    || { echo "   ✗ ensure-db-user 执行失败：库/角色未就绪（helper 的 stderr 有详情）"; exit 1; }
  ( umask 077
    cat > "$ENV_FILE" <<EOF
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://semgravio-pg:5432/$DB_SERVICE
SPRING_DATASOURCE_USERNAME=$DB_SERVICE
SPRING_DATASOURCE_PASSWORD=$db_pw
REDIS_HOST=semgravio-redis
REDIS_PORT=6379
REDIS_PASSWORD=$redis_pw
APP_REGISTRY_API_SECRET_MASTER_KEY=$master_key
EOF
  )
  chmod 600 "$ENV_FILE"
  echo "   ✓ env 已创建（600）：独立库+低权限角色就绪；master key 仅存在于服务器，请立即离线备份到密码管理器"
fi

echo "-- env 全量校验（每次发布，应用启动前；失败即中止——绝不自动换 master key“修复”）"
set -a; . "./$ENV_FILE"; set +a   # env 为 shell 形态（本脚本 600 落盘），源入后逐项校验
env_bad=0
for v in SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD \
         REDIS_HOST REDIS_PORT REDIS_PASSWORD APP_REGISTRY_API_SECRET_MASTER_KEY; do
  if [ -z "${!v:-}" ]; then
    echo "   ✗ $ENV_FILE 缺 $v（或为空）"
    env_bad=1
  fi
done
if [ -n "${APP_REGISTRY_API_SECRET_MASTER_KEY:-}" ]; then
  # 非法 Base64 时 base64 -d 非零（pipefail 下会带崩赋值）——吞状态只取解码长度，长度≠32 即拒
  mk_len=$(printf '%s' "$APP_REGISTRY_API_SECRET_MASTER_KEY" | base64 -d 2>/dev/null | wc -c | tr -d '[:space:]') || true
  if [ "$mk_len" != "32" ]; then
    echo "   ✗ APP_REGISTRY_API_SECRET_MASTER_KEY 非法：须 Base64 且解码恰好 32 字节（当前解码 $mk_len 字节）"
    env_bad=1
  fi
fi
[ "$env_bad" = 0 ] || { echo "   修正 $ENV_FILE 后重跑发布；校验失败不会自动重生成 master key（历史 apiSecret 密文依赖它）"; exit 1; }
echo "   ✓ env 校验通过（必需变量齐全，master key 为 32 字节 Base64）"

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
