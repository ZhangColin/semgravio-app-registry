#!/usr/bin/env bash
# release 主链命令替身验收（issue #27 AC14 ＋ #28 首次发布自动准备 env ＋ #29 发布前数据库
# 备份 ＋ #30 admin-console apiSecret 凭证反馈）——不连接真实生产服务器：ssh/scp/docker
# 全部走本目录 stub-bin/ 替身，「服务器」行为由 STUB_* 场景变量控制，远端脚本在一次性
# $STUB_HOME 里本地执行。断言只看外部行为：退出码、终端输出、部署包内容、服务器侧 env
# 文件内容/权限、Compose 渲染配置——不断言脚本内部实现。
#
# 覆盖：① 部署包（jar/REVISION/无 env 无 secret/LF）② 脏工作区门禁 ③ Compose 无端口暴露
#       ④ Redis requirepass 契约 ⑤ SSH/infra/webnet/env 门禁 ⑥ 健康失败非零 + 成功路径
#       ⑦ 首次发布自动准备 env（ensure-db-user 契约/凭据正本/全量校验/secret 不落地输出/
#       重复发布复用）⑧ 发布前备份（默认备份/SKIP_BACKUP 跳过/备份失败中止——server.sh
#       运维子命令的验收在 server-ops.sh）⑨ 凭证反馈（created/rotated/无事件/日志不可用，
#       --since 时间窗不重放历史、不捎带其他 secret）。
#
# 注意：① 打包用真 Maven 构建且要求干净工作区（部署包来自已提交源码），
#       ⑤⑥⑦⑧ 复用①/② 产出的 dist/ 包（SKIP_BUILD=1）。本脚本所有临时文件 trap 清理，
#       中途失败不会弄脏工作区。
# 用法（仓库根）：bash deploy/tests/release-chain.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
STUB_BIN="$ROOT/deploy/tests/stub-bin"
JAR_NAME="semgravio-app-registry-1.0.0-SNAPSHOT.jar"

# 合法 32 字节 master key（Base64）——⑥⑦ 场景 env 夹具共用；生成值只进 env，不进断言文本
MK_VALID=$(openssl rand -base64 32)

TMP_BASE=$(mktemp -d)
cleanup() {
  rm -rf "$TMP_BASE"
  rm -f "$ROOT/.rehearsal-dirty-probe"
  [ "${CREATED_ENV:-0}" = 1 ] && rm -f "$ROOT/.env.production"
  docker rm -f ar-rehearsal-redis >/dev/null 2>&1 || true
}
trap cleanup EXIT

. "$ROOT/deploy/tests/lib.sh"   # ok/die/phase + CHMOD_EFFECTIVE + new_stub_home

# ---------------------------------------------------------------- ① 打包（真构建）
phase "1/9 部署包（Maven 真构建，须干净工作区）"
[ -z "$(git status --porcelain)" ] || die "工作区不干净，先提交再跑（部署包来自已提交源码）"
[ "$(git rev-parse --abbrev-ref HEAD)" != "main" ] || die "拒绝在 main 上打包发布"
bundle_out=$(bash deploy/build-bundle.sh 2>&1) || { echo "$bundle_out" >&2; die "build-bundle.sh 退出非零"; }
BUNDLE=$(tail -n 1 <<<"$bundle_out")
[ -f "$BUNDLE" ] || die "包文件不存在：$BUNDLE"
ok "打包成功：$BUNDLE"

entries=$(tar -tzf "$BUNDLE")
# 条目挂在 <stamp>/ 顶层目录下（服务器侧 --strip-components=1 解包）
grep -qE '(^|/)REVISION$' <<<"$entries" || die "包内缺 REVISION"
grep -qE "(^|/)target/$JAR_NAME\$" <<<"$entries" || die "包内缺可执行 jar"
grep -qE '(^|/)Dockerfile$' <<<"$entries" || die "包内缺 Dockerfile"
grep -qE '(^|/)docker-compose\.prod\.yml$' <<<"$entries" || die "包内缺 docker-compose.prod.yml"
grep -qE '(^|/)deploy/release\.sh$' <<<"$entries" || die "包内缺 deploy/release.sh"
grep -qE '(^|/)deploy/server\.sh$' <<<"$entries" || die "包内缺 deploy/server.sh（运维入口）"
grep -qE '(^|/)\.env\.production\.example$' <<<"$entries" || die "包内缺 env 模板"
leak=$(grep -Ei '(^|/)(\.env\.production|\.env\.local)$|\.pem$|id_rsa' <<<"$entries" || true)
[ -z "$leak" ] || die "包内出现 env/secret 形态文件：$leak"
# 部署包不得携带 backups/（解包 --strip-components=1 落进应用目录，会覆盖现网备份正本）
! grep -qE '(^|/)backups/' <<<"$entries" || die "包内出现 backups/（会覆盖服务器备份目录）"
ok "包内容：jar + Dockerfile + compose + 发布/运维脚本 + env 模板 + REVISION；无 .env*/密钥/backups"

EXTRACT="$TMP_BASE/extract"
mkdir -p "$EXTRACT"
tar -xzf "$BUNDLE" -C "$EXTRACT"
PKG_ROOT="$EXTRACT/$(basename "$BUNDLE" .tar.gz)"
[ -s "$PKG_ROOT/target/$JAR_NAME" ] || die "jar 为空"
unzip -l "$PKG_ROOT/target/$JAR_NAME" | grep -q 'BOOT-INF' || die "jar 非 Spring Boot fat jar"
ok "jar 非空且为可执行 fat jar（BOOT-INF）"

# 字节级 CR 检测（tr 自带 \r 转义，不经 bash $''）——勿改回 grep $'\r'：Git Bash 5.3 下
# 脚本文件里的 $'\r' 会异常展开为空串导致 grep 空模式误匹配全部文件（bash -c 下正常，已踩坑）
crlf=$(find "$PKG_ROOT" -name '*.sh' -type f | while IFS= read -r f; do
  [ -n "$(LC_ALL=C tr -cd '\r' < "$f")" ] && printf '%s\n' "$f"
done)
[ -z "$crlf" ] || die "包内 shell 脚本出现 CRLF：$crlf"
ok "包内 *.sh 全部 LF"

rev=$(cat "$PKG_ROOT/REVISION")
[ "$rev" = "$(git rev-parse HEAD)" ] || die "REVISION($rev) ≠ HEAD($(git rev-parse HEAD))"
ok "REVISION 与 HEAD 一致：${rev:0:8}"

# ---------------------------------------------------------------- ② 脏工作区门禁
phase "2/9 脏工作区门禁（ALLOW_DIRTY 逃生开关）"
touch "$ROOT/.rehearsal-dirty-probe"
dirty_out=$(ALLOW_DIRTY=0 SKIP_MVN=1 bash deploy/build-bundle.sh 2>&1); dirty_rc=$?
[ "$dirty_rc" -ne 0 ] || die "脏工作区竟然打包成功"
grep -q "未提交改动" <<<"$dirty_out" || die "脏工作区拒绝信息不明确：$dirty_out"
grep -q ".rehearsal-dirty-probe" <<<"$dirty_out" || die "拒绝信息未列出脏文件"
ok "默认拒绝脏工作区（非零退出 + 列出脏文件）"

allow_out=$(ALLOW_DIRTY=1 SKIP_MVN=1 bash deploy/build-bundle.sh 2>&1); allow_rc=$?
[ "$allow_rc" -eq 0 ] || { echo "$allow_out" >&2; die "ALLOW_DIRTY=1 仍失败"; }
grep -q "警告" <<<"$allow_out" || die "ALLOW_DIRTY=1 缺警告"
ALLOW_BUNDLE=$(tail -n 1 <<<"$allow_out")
[ "$(tar -xzf "$ALLOW_BUNDLE" -O --wildcards '*/REVISION')" = "$(git rev-parse HEAD)" ] \
  || die "ALLOW_DIRTY 包的源码树未按 HEAD 打包"
! tar -tzf "$ALLOW_BUNDLE" | grep -qE '(^|/)\.rehearsal-dirty-probe$' || die "ALLOW_DIRTY 包混入未提交文件"
ok "ALLOW_DIRTY=1 放行但明确警告；源码树仍按 HEAD，脏文件不入包"
rm -f "$ROOT/.rehearsal-dirty-probe"

# ---------------------------------------------------------------- ③ Compose 外部行为
phase "3/9 Compose 外部行为（render 断言：内网 only）"
command -v docker >/dev/null 2>&1 || die "docker 不可用（compose render 断言需要）"
if [ ! -f "$ROOT/.env.production" ]; then touch "$ROOT/.env.production"; CREATED_ENV=1; fi
rendered=$(docker compose -f docker-compose.prod.yml config 2>/dev/null) \
  || die "docker compose config 渲染失败"
! grep -q 'published' <<<"$rendered" || die "存在宿主端口映射（published）——必须内网 only"
grep -q 'container_name: semgravio-app-registry' <<<"$rendered" || die "容器名未显式声明为 semgravio-app-registry"
grep -q 'name: semgravio-app-registry' <<<"$rendered" || die "compose 项目名未显式声明"
grep -q 'image: semgravio-app-registry' <<<"$rendered" || die "镜像名未显式声明"
grep -q 'external: true' <<<"$rendered" || die "webnet 未声明 external（不得由本应用创建网络）"
grep -q 'restart: unless-stopped' <<<"$rendered" || die "缺自动重启策略"
grep -q '8088/actuator/health' <<<"$rendered" || die "缺 actuator 健康检查"
grep -q 'max-size' <<<"$rendered" || die "缺日志轮转"
! grep -qi 'previewnet' <<<"$rendered" || die "不得加入 previewnet"
! grep -q 'semgravio-openapi' <<<"$rendered" || die "出现旧名 semgravio-openapi"
[ "$(grep -c 'container_name:' <<<"$rendered")" -eq 1 ] || die "compose 应只编排本应用一个容器"
ok "无宿主端口、external webnet、显式 name/image/container、重启/健康/日志轮转齐全、无旧名/previewnet"

# ---------------------------------------------------------------- ④ Redis requirepass 契约
phase "4/9 Redis requirepass 契约"
grep -q 'password: ${REDIS_PASSWORD}' src/main/resources/application-prod.yml \
  || die "application-prod.yml 未显式绑定 REDIS_PASSWORD"
grep -q '^REDIS_PASSWORD=' .env.production.example \
  || die "env 模板缺 REDIS_PASSWORD"
! grep -Eq 'postgres-server|redis-cache|SERVER_PASSWORD|OPENAPI_APP_(ID|SECRET)' .env.production.example \
  || die "env 模板仍残留旧地址/服务器密码/OpenAPI 客户端变量"
ok "prod 配置显式消费 REDIS_PASSWORD；env 模板已清旧地址/密码/无关变量"

docker image inspect redis:7.4.12-alpine >/dev/null 2>&1 \
  || docker pull -q redis:7.4.12-alpine >/dev/null || die "拉取 redis:7.4.12-alpine 失败"
docker run -d --name ar-rehearsal-redis redis:7.4.12-alpine \
  redis-server --requirepass rehearsal-pass --save "" --appendonly no >/dev/null \
  || die "起排练 redis 失败"
pong=$(docker run --rm --network container:ar-rehearsal-redis redis:7.4.12-alpine \
  redis-cli -a rehearsal-pass --no-auth-warning ping 2>/dev/null || true)
[ "$pong" = "PONG" ] || die "带密码未 PONG（共享 Redis requirepass 契约不满足）：$pong"
noauth=$(docker run --rm --network container:ar-rehearsal-redis redis:7.4.12-alpine \
  redis-cli --no-auth-warning ping 2>&1 || true)
grep -q NOAUTH <<<"$noauth" || die "无密码竟未拒（requirepass 未生效）：$noauth"
docker rm -f ar-rehearsal-redis >/dev/null
ok "requirepass 契约：带密码 PONG、无密码 NOAUTH"

# ---------------------------------------------------------------- ⑤ 门禁（ssh/scp/docker 替身）
phase "5/9 SSH/infra/webnet/env 门禁（应用变更前非零退出）"
[ -n "${BUNDLE:-}" ] || die "① 未产出包，无法继续"

run_release() { # 场景 env 由调用方 export；统一覆盖连接参数 + SKIP_BUILD 复用①的包
  PATH="$STUB_BIN:$PATH" \
  SERVER_HOST=stub SERVER_USER=stub SSH_KEY="$STUB_KEY" \
  REMOTE_DIR=semgravio-app-registry INFRA_DIR=semgravio-infra \
  SKIP_BUILD=1 bash deploy/release.sh
}
no_compose_called() { ! grep -q '^compose' "$STUB_DOCKER_LOG"; }

new_stub_home
out=$(SSH_KEY="$STUB_HOME/none.pem" PATH="$STUB_BIN:$PATH" SERVER_HOST=stub SERVER_USER=stub \
      REMOTE_DIR=app INFRA_DIR=infra SKIP_BUILD=1 bash deploy/release.sh 2>&1); rc=$?
[ "$rc" -ne 0 ] && grep -q "密钥" <<<"$out" || die "缺密钥场景应非零退出并提示密钥：rc=$rc"
ok "SSH 私钥缺失 → 非零退出"

new_stub_home
out=$(STUB_SSH_FAIL=1 run_release 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "SSH 不可达场景应非零退出"
grep -q "前置检查" <<<"$out" || die "SSH 失败信息不明确：$out"
ok "SSH 连通预检失败 → 非零退出"

new_stub_home
out=$(STUB_INFRA_UNHEALTHY="semgravio-redis" run_release 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "infra 不健康场景应非零退出"
grep -q "✗ semgravio-redis" <<<"$out" || die "未报告 semgravio-redis 不健康：$out"
no_compose_called || die "infra 门禁失败后仍动了应用（compose 被调用）"
[ ! -f "$STUB_HOME/semgravio-app-registry/REVISION" ] || die "门禁失败却已解包应用"
ok "semgravio-redis 不健康 → 应用变更前非零退出（无 compose/无解包）"

new_stub_home
out=$(STUB_NO_WEBNET=1 run_release 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "webnet 缺失场景应非零退出"
grep -q "webnet" <<<"$out" || die "未报告 webnet 缺失：$out"
no_compose_called || die "webnet 缺失失败后仍动了应用"
ok "webnet 缺失 → 应用变更前非零退出"

new_stub_home
out=$(run_release 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "无 env 且无 infra 建号 helper 场景应非零退出"
grep -q "ensure-db-user" <<<"$out" || die "未提示缺 infra 建号 helper：$out"
no_compose_called || die "env 准备失败仍动了应用"
ok "无 env 且 infra 不完整 → 应用变更前非零退出（提示先起共享 infra；#28 自动准备的前提）"

new_stub_home
mkdir -p "$STUB_HOME/semgravio-app-registry"
printf 'SPRING_DATASOURCE_URL=x\nSPRING_DATASOURCE_USERNAME=x\nSPRING_DATASOURCE_PASSWORD=x\n' \
  > "$STUB_HOME/semgravio-app-registry/.env.production"
out=$(run_release 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "env 缺 REDIS_PASSWORD 场景应非零退出"
grep -q "REDIS_PASSWORD" <<<"$out" || die "未提示缺 REDIS_PASSWORD：$out"
no_compose_called || die "env 缺密码失败后仍动了应用"
ok "env 缺 REDIS_PASSWORD → 非零退出（共享 Redis 已 requirepass）"

# ⑥⑦ 共用的合法 env 夹具（master key 为 32 字节 Base64；值无所谓，非空且通过校验即可）
write_valid_env() { # $1=目标 env 路径
  cat > "$1" <<EOF
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://semgravio-pg:5432/semgravio_app_registry
SPRING_DATASOURCE_USERNAME=semgravio_app_registry
SPRING_DATASOURCE_PASSWORD=some-db-pw
REDIS_HOST=semgravio-redis
REDIS_PORT=6379
REDIS_PASSWORD=some-redis-pw
APP_REGISTRY_API_SECRET_MASTER_KEY=$MK_VALID
EOF
}

# ---------------------------------------------------------------- ⑥ 健康验收 + 成功路径
phase "6/9 健康验收（失败非零 + 日志入口）与成功路径"

new_stub_home
mkdir -p "$STUB_HOME/semgravio-app-registry"
write_valid_env "$STUB_HOME/semgravio-app-registry/.env.production"
out=$(STUB_HEALTH_FAIL=1 HEALTH_WAIT_SECONDS=4 run_release 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "健康超时场景应非零退出（不得只警告报成功）"
grep -q "docker logs" <<<"$out" || die "健康失败未提示日志入口：$out"
grep -q '^compose' "$STUB_DOCKER_LOG" || die "健康失败场景 compose 未被调用（替身/脚本逻辑劈叉）"
ok "健康验收失败 → 非零退出 + 日志入口提示"

new_stub_home
mkdir -p "$STUB_HOME/semgravio-app-registry"
write_valid_env "$STUB_HOME/semgravio-app-registry/.env.production"
# 预置一份既有备份：发布解包不得覆盖/清除服务器备份目录（#29）
mkdir -p "$STUB_HOME/semgravio-app-registry/backups"
touch "$STUB_HOME/semgravio-app-registry/backups/pre-existing.sql.gz"
out=$(run_release 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "全绿场景应零退出"; }
grep -q "REVISION:" <<<"$out" || die "成功输出缺 REVISION：$out"
grep -q "semgravio-app-registry healthy" <<<"$out" || die "成功输出缺健康验收：$out"
grep -q "已上线" <<<"$out" || die "成功输出缺完成提示：$out"
grep -q 'compose -f docker-compose.prod.yml up -d --build' "$STUB_DOCKER_LOG" \
  || die "compose 未按 up -d --build 调用"
! grep -q ' down ' "$STUB_DOCKER_LOG" || die "不得先 down 旧容器"
[ -f "$STUB_HOME/semgravio-app-registry/REVISION" ] || die "成功后未解包"
# 发布前备份（#29）：默认产出，先于应用变更（pg_dump 早于 compose），既有备份不被解包覆盖
BK_DIR="$STUB_HOME/semgravio-app-registry/backups"
bk_file=$(ls "$BK_DIR"/semgravio_app_registry-[0-9]*.sql.gz 2>/dev/null | head -1)
[ -n "$bk_file" ] || die "默认发布未产出时间戳备份文件"
[ -f "$BK_DIR/pre-existing.sql.gz" ] || die "既有备份被部署包解包覆盖/清除"
gunzip -c "$bk_file" | grep -q 'PostgreSQL database dump complete' || die "备份内容不完整（缺 complete 收尾）"
grep -q -- '-e PGPASSWORD=some-db-pw semgravio-pg pg_dump -U semgravio_app_registry -d semgravio_app_registry' \
  "$STUB_DOCKER_LOG" || die "备份边界/凭据不对（应为本服务库+角色+env 密码）：$(grep pg_dump "$STUB_DOCKER_LOG")"
dump_ln=$(grep -n pg_dump "$STUB_DOCKER_LOG" | head -1 | cut -d: -f1)
compose_ln=$(grep -n '^compose' "$STUB_DOCKER_LOG" | head -1 | cut -d: -f1)
[ "${dump_ln:-0}" -lt "${compose_ln:-9999}" ] || die "备份未先于应用变更（pg_dump 第${dump_ln:-?}行 vs compose 第${compose_ln:-?}行）"
ok "成功路径：发布前备份 → 解包 → compose up -d --build（不先 down）→ 健康验收 → 完成"

# ---------------------------------------------------------------- ⑦ 首次发布自动准备 env（#28）
phase "7/9 首次发布自动准备 env（ensure-db-user 契约 / 凭据正本 / 全量校验 / secret 不落地）"

# 共享 infra 最小替身：凭据正本（超管密码只用于断言发布链绝不取用）＋ ensure-db-user 契约替身
stub_infra() {
  mkdir -p "$STUB_HOME/semgravio-infra"
  printf 'POSTGRES_USER=aiplatform\nPOSTGRES_PASSWORD=infra-super-secret\nREDIS_PASSWORD=infra-redis-secret\n' \
    > "$STUB_HOME/semgravio-infra/.env"
  cat > "$STUB_HOME/semgravio-infra/ensure-db-user.sh" <<'EOS'
#!/usr/bin/env bash
# 契约替身：校验 DB_PASSWORD 注入并记录调用；stdout 打印凭据分片——release 侧必须吞掉，不得进终端
set -u
svc="${1:?}"
[ -n "${DB_PASSWORD:-}" ] || { echo "DB_PASSWORD missing" >&2; exit 1; }
printf 'ensure-db-user svc=%s db_password=%s\n' "$svc" "$DB_PASSWORD" >> "$STUB_HOME/ensure-db-user.log"
cat <<EOF
DB_HOST=semgravio-pg
DB_PORT=5432
DB_NAME=$svc
DB_USER=$svc
DB_PASSWORD=$DB_PASSWORD
EOF
EOS
  chmod +x "$STUB_HOME/semgravio-infra/ensure-db-user.sh"
}

# 首次发布全绿：无 env + infra 替身就绪 → 自动建号、服务器侧 600 落盘、健康验收通过
new_stub_home
stub_infra
out=$(run_release 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "首次发布全绿场景应零退出"; }
ENV_FILE="$STUB_HOME/semgravio-app-registry/.env.production"
[ -f "$ENV_FILE" ] || die "首次发布未创建 env"
if [ "$CHMOD_EFFECTIVE" = "1" ]; then
  [ "$(stat -c '%a' "$ENV_FILE")" = "600" ] || die "env 权限非 600：$(stat -c '%a' "$ENV_FILE")"
  ok "env 权限 600"
else
  ok "本机 chmod 为建议性（Git Bash noacl）：跳过 600 位断言，脚本始终 chmod 600（Linux 生效）"
fi
grep -q '^SPRING_DATASOURCE_URL=jdbc:postgresql://semgravio-pg:5432/semgravio_app_registry$' "$ENV_FILE" \
  || die "env 数据源 URL 不是独立库 semgravio_app_registry"
grep -q '^SPRING_DATASOURCE_USERNAME=semgravio_app_registry$' "$ENV_FILE" || die "env 库角色名不对"
grep -q '^REDIS_HOST=semgravio-redis$' "$ENV_FILE" || die "env Redis 主机不对"
grep -q '^REDIS_PASSWORD=infra-redis-secret$' "$ENV_FILE" || die "env Redis 密码未取自 infra 凭据正本"
db_pw=$(grep '^SPRING_DATASOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)
master_key=$(grep '^APP_REGISTRY_API_SECRET_MASTER_KEY=' "$ENV_FILE" | cut -d= -f2-)
[ -n "$db_pw" ] || die "env 缺自动生成的数据库密码"
[ -n "$master_key" ] || die "env 缺自动生成的 master key"
[ "$(printf '%s' "$master_key" | base64 -d 2>/dev/null | wc -c | tr -d '[:space:]')" = 32 ] \
  || die "master key 非「Base64 编码的 32 字节」"
helper_log=$(cat "$STUB_HOME/ensure-db-user.log")
grep -q 'svc=semgravio_app_registry' <<<"$helper_log" || die "ensure-db-user 未被调用：$helper_log"
grep -q "db_password=$db_pw" <<<"$helper_log" || die "ensure-db-user 未收到生成的库密码"
# 首次发布同样默认备份，且用的是本次自动生成的库密码（#28+#29 衔接）
grep -q -- "-e PGPASSWORD=$db_pw semgravio-pg pg_dump -U semgravio_app_registry -d semgravio_app_registry" \
  "$STUB_DOCKER_LOG" || die "首次发布的发布前备份未用自动生成的库密码：$(grep pg_dump "$STUB_DOCKER_LOG")"
! grep -q 'POSTGRES_PASSWORD' <<<"$out" || die "超管密码字样出现在发布终端输出"
! grep -qF "$db_pw" <<<"$out" || die "数据库密码泄漏进发布终端输出"
! grep -qF "$master_key" <<<"$out" || die "master key 泄漏进发布终端输出"
! grep -qF 'infra-redis-secret' <<<"$out" || die "Redis 密码泄漏进发布终端输出"
ok "首次发布：ensure-db-user 自动建号 + env 600 落盘 + 正本取 Redis 密码 + 建号后即备份；三类 secret 均不在终端输出"

# 重复发布：复用既有 env 与全部凭据，不重置 master key、不重跑建号、不被部署包覆盖
cp "$ENV_FILE" "$TMP_BASE/env-first.bak"
out=$(run_release 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "重复发布应零退出"; }
cmp -s "$ENV_FILE" "$TMP_BASE/env-first.bak" || die "重复发布后 env 被改动（master key 终身稳定，#26 决策 10）"
[ "$(grep -c '' "$STUB_HOME/ensure-db-user.log")" -eq 1 ] || die "重复发布不应重跑 ensure-db-user（幂等复用）"
[ "$(grep -c 'pg_dump' "$STUB_DOCKER_LOG")" -eq 2 ] || die "每次发布都应备份一次（两次发布应有两次 pg_dump）"
ok "重复发布：env 逐字节不变、ensure-db-user 不重跑、每次发布前都备份"

# 首次发布：infra 凭据正本缺 REDIS_PASSWORD 行 → 明确报错非零退出（管道无匹配不得 set -e 静默终止）
new_stub_home
stub_infra
sed -i '/^REDIS_PASSWORD=/d' "$STUB_HOME/semgravio-infra/.env"
out=$(run_release 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "infra 正本缺 REDIS_PASSWORD 场景应非零退出"
grep -q "REDIS_PASSWORD" <<<"$out" || die "未提示凭据正本缺 REDIS_PASSWORD（静默终止？）：$out"
no_compose_called || die "正本缺 Redis 密码失败后仍动了应用"
[ ! -f "$STUB_HOME/semgravio-app-registry/.env.production" ] || die "失败后不应留下半成品 env"
[ ! -f "$STUB_HOME/ensure-db-user.log" ] || die "正本缺 Redis 密码不应已动 infra（建号已执行）"
ok "infra 凭据正本缺 REDIS_PASSWORD → 明确报错非零退出（无 compose/无半成品 env/未动 infra）"

# 校验失败矩阵：应用启动前非零退出、env 原样不动（绝不自动换 master key“修复”）
vfail() { # $1=描述 $2=env 内容 $3=预期输出关键字
  local out rc
  new_stub_home
  mkdir -p "$STUB_HOME/semgravio-app-registry"
  printf '%s' "$2" > "$STUB_HOME/semgravio-app-registry/.env.production"
  cp "$STUB_HOME/semgravio-app-registry/.env.production" "$TMP_BASE/vfail.bak"
  out=$(run_release 2>&1); rc=$?
  [ "$rc" -ne 0 ] || die "$1：应非零退出"
  grep -q "$3" <<<"$out" || die "$1：未提示 $3：$out"
  no_compose_called || die "$1：校验失败后仍动了应用（compose 被调用）"
  ! grep -q 'pg_dump' "$STUB_DOCKER_LOG" || die "$1：env 校验失败不应已跑备份"
  cmp -s "$STUB_HOME/semgravio-app-registry/.env.production" "$TMP_BASE/vfail.bak" \
    || die "$1：校验失败改动了 env（绝不允许自动换 master key“修复”）"
  ok "$1 → 非零退出 + env 原样不动"
}

valid_env=$(write_valid_env /dev/stdout)
vfail "master key 非法 Base64" \
  "$(printf '%s' "$valid_env" | sed "s|^APP_REGISTRY_API_SECRET_MASTER_KEY=.*|APP_REGISTRY_API_SECRET_MASTER_KEY=not-base64!!!|")" \
  "MASTER_KEY"
vfail "master key 解码非 32 字节" \
  "$(printf '%s' "$valid_env" | sed "s|^APP_REGISTRY_API_SECRET_MASTER_KEY=.*|APP_REGISTRY_API_SECRET_MASTER_KEY=$(openssl rand -base64 16)|")" \
  "32"
vfail "REDIS_PASSWORD 为空" \
  "$(printf '%s' "$valid_env" | sed 's|^REDIS_PASSWORD=.*|REDIS_PASSWORD=|')" \
  "REDIS_PASSWORD"
vfail "缺 SPRING_DATASOURCE_PASSWORD" \
  "$(printf '%s' "$valid_env" | grep -v '^SPRING_DATASOURCE_PASSWORD=')" \
  "SPRING_DATASOURCE_PASSWORD"

# ---------------------------------------------------------------- ⑧ 发布前备份开关与失败中止（#29）
phase "8/9 发布前备份（SKIP_BACKUP 跳过 / 备份失败中止）"

new_stub_home
mkdir -p "$STUB_HOME/semgravio-app-registry"
write_valid_env "$STUB_HOME/semgravio-app-registry/.env.production"
out=$(SKIP_BACKUP=1 run_release 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "SKIP_BACKUP=1 发布应零退出"; }
! grep -q 'pg_dump' "$STUB_DOCKER_LOG" || die "SKIP_BACKUP=1 仍执行了 pg_dump"
grep -q "跳过" <<<"$out" || die "SKIP_BACKUP=1 未在输出明示跳过：$out"
grep -q '^compose' "$STUB_DOCKER_LOG" || die "SKIP_BACKUP=1 不应影响后续发布链"
ok "SKIP_BACKUP=1 → 显式跳过发布前备份，发布链照常"

new_stub_home
mkdir -p "$STUB_HOME/semgravio-app-registry"
write_valid_env "$STUB_HOME/semgravio-app-registry/.env.production"
out=$(STUB_DUMP_FAIL=1 run_release 2>&1); rc=$?
[ "$rc" -ne 0 ] || { echo "$out" >&2; die "备份失败应非零退出（不得误报成功）"; }
grep -q "备份失败" <<<"$out" || die "备份失败未明确报错：$out"
no_compose_called || die "备份失败后仍动了应用（compose 被调用）"
[ ! -f "$STUB_HOME/semgravio-app-registry/REVISION" ] || die "备份失败却已解包应用"
leftover=$(find "$STUB_HOME/semgravio-app-registry/backups" \
             \( -name '*.sql.gz' -o -name '*.tmp' \) -print 2>/dev/null || true)
[ -z "$leftover" ] || die "备份失败留下残缺/临时文件（可能被误报为成功）：$leftover"
ok "pg_dump 中途失败 → 应用变更前中止（无 compose/无解包/无残缺备份留存）"

# ---------------------------------------------------------------- ⑨ 凭证反馈（#30）
phase "9/9 凭证反馈（created / rotated / 无事件 / 日志不可用；不重放历史；不捎带其他 secret）"

# 场景底座：合法 env（⑥⑦ 同款夹具）——凭证反馈只看启动后日志窗，与 env 生成路径无关
seed_stage() {
  new_stub_home
  mkdir -p "$STUB_HOME/semgravio-app-registry"
  write_valid_env "$STUB_HOME/semgravio-app-registry/.env.production"
}

# 时间窗口径：全链恰好一次 docker logs，且必须带 --since（RFC3339 UTC）限定本次启动——
# 不带 --since 的全量日志会把历史凭证日志一并取回（STUB_SEED_OLD 场景据此判死刑）
assert_logs_window() {
  local n_logs n_since
  n_logs=$(grep -c '^logs ' "$STUB_DOCKER_LOG")
  n_since=$(grep -cE '^logs --since [0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z semgravio-app-registry$' \
              "$STUB_DOCKER_LOG" || true)
  [ "$n_logs" = "1" ] && [ "$n_since" = "1" ] \
    || die "凭证反馈应恰好一次 docker logs --since <RFC3339 UTC>（实际 logs 调用 $n_logs 次、带时间窗 $n_since 次）：$(grep '^logs ' "$STUB_DOCKER_LOG")"
}

# 时间窗日志里混入 db/redis/master key 噪声（若发布把整窗日志倒进终端、而非只挑 seed 凭证行，此处泄漏）
SEED_DECOY="db=some-db-pw redis=some-redis-pw mk=$MK_VALID"
# stub 事件里的 apiSecret 用运行时随机值（部署包含 HEAD 全仓源码，字面量会被下面的全目录扫描误伤）
CREATED_SECRET="rehearsal-created-$(openssl rand -hex 24)"
ROTATED_SECRET="rehearsal-rotated-$(openssl rand -hex 24)"

seed_stage
out=$(STUB_SEED_CREATED=1 STUB_SEED_CREATED_SECRET="$CREATED_SECRET" STUB_LOG_DECOY="$SEED_DECOY" run_release 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "created 场景应零退出"; }
grep -q "首次创建" <<<"$out" || die "created 事件未明确反馈首次创建：$out"
grep -qF "$CREATED_SECRET" <<<"$out" || die "created 未显示明文 apiSecret：$out"
grep -q "apiKey" <<<"$out" || die "created 未显示 apiKey：$out"
grep -q "签名客户端" <<<"$out" || die "created 未说明 admin-console 配置用途：$out"
assert_logs_window
! grep -qF 'some-db-pw' <<<"$out" || die "数据库密码随凭证反馈泄漏进发布输出"
! grep -qF 'some-redis-pw' <<<"$out" || die "Redis 密码随凭证反馈泄漏进发布输出"
! grep -qF "$MK_VALID" <<<"$out" || die "master key 随凭证反馈泄漏进发布输出"
# apiSecret 只许进终端：服务器侧（替身家目录＝应用目录）任何文件都不得含明文（AC「不写入
# 部署包或额外的本地凭据文件」；部署包本身来自发布前 HEAD，phase ① 已断言包内无 secret 形态文件）
! grep -rIlF "$CREATED_SECRET" "$STUB_HOME" \
  || die "apiSecret 明文落进了服务器侧文件：$(grep -rIlF "$CREATED_SECRET" "$STUB_HOME")"
ok "created：显示 admin-console 的 apiKey/apiSecret 与配置用途；日志窗内其他 secret 不外带、明文不落任何文件"

seed_stage
out=$(STUB_SEED_ROTATED=1 STUB_SEED_ROTATED_SECRET="$ROTATED_SECRET" STUB_LOG_DECOY="$SEED_DECOY" run_release 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "rotated 场景应零退出"; }
grep -q "已轮换" <<<"$out" || die "rotated 事件未明确反馈轮换：$out"
grep -qF "$ROTATED_SECRET" <<<"$out" || die "rotated 未显示新 apiSecret：$out"
grep -q "更新" <<<"$out" || die "rotated 未提示更新 admin-console 配置：$out"
grep -q "admin-console" <<<"$out" || die "rotated 未指明凭证属于 admin-console：$out"
assert_logs_window
! grep -qF 'some-db-pw' <<<"$out" || die "rotated 场景泄漏数据库密码"
! grep -qF 'some-redis-pw' <<<"$out" || die "rotated 场景泄漏 Redis 密码"
! grep -qF "$MK_VALID" <<<"$out" || die "rotated 场景泄漏 master key"
ok "rotated：显示新 apiSecret 并提示更新 admin-console 配置；其他 secret 不外带"

# 无事件（普通重复发布）：不得伪造凭证或误导性成功；STUB_SEED_OLD 证明历史凭证日志不被重放
seed_stage
out=$(STUB_SEED_OLD=1 STUB_LOG_DECOY="$SEED_DECOY" run_release 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "无事件场景应零退出"; }
grep -q "无新凭证事件" <<<"$out" || die "无事件时未如实说明：$out"
grep -q "幂等" <<<"$out" || die "无事件说明未指出 seed 幂等跳过：$out"
# 健康探活可早于 seed 落日志（web 先起、ApplicationRunner 后跑）：无事件分支须自带自查命令兜底，
# 首次发布命中该窄窗时不至于被「沿用既有凭证」误导
grep -q "自查" <<<"$out" || die "无事件分支缺自查命令兜底（seed 竞态提示）：$out"
! grep -qF 'stub-old-secret-from-previous-boot' <<<"$out" || die "重放了历史凭证日志（旧 apiSecret 不应出现）"
# 凭证形态＝apiSecret 冒号后紧跟 Base64URL 长串（Git Bash grep 对多字节括号类按字节匹配：
# [：:] 只吃全角冒号/逗号首字节，「apiSecret，」会误命中——多字节字面量放括号类外才整串生效）
! grep -qE 'apiSecret[[:space:]]*(：|:)[[:space:]]*[A-Za-z0-9_-]{16,}' <<<"$out" \
  || die "无事件却输出凭证形态的 apiSecret 行：$out"
! grep -qF 'some-db-pw' <<<"$out" || die "无事件场景泄漏数据库密码"
assert_logs_window
ok "无事件：如实说明幂等跳过；不重放历史凭证、无伪造凭证输出"

# 日志不可用：已健康的发布不因此失败，且「无法检查」与「无事件」在输出上可区分、不误报凭证成功
seed_stage
out=$(STUB_LOGS_FAIL=1 run_release 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "日志不可用不应把已健康的发布拖成失败（rc=$rc）"; }
grep -q "无法检查" <<<"$out" || die "日志不可用未明确报出：$out"
! grep -q "无新凭证事件" <<<"$out" || die "日志不可用被误报成「无新凭证事件」（两者必须可区分）"
! grep -q "首次创建" <<<"$out" || die "日志不可用时误报凭证生成成功"
! grep -q "已轮换" <<<"$out" || die "日志不可用时误报凭证轮换"
grep -q "docker logs" <<<"$out" || die "日志不可用缺人工自查指引（日志入口）：$out"
grep -q "已上线" <<<"$out" || die "日志检查失败不应影响发布完成提示"
grep -q '^logs ' "$STUB_DOCKER_LOG" || die "日志不可用场景应确实尝试过采集日志"
ok "日志不可用：发布仍成功收尾，输出区分「无法检查」≠「无事件」，不误报凭证成功"

# ---------------------------------------------------------------- 旧入口退役
phase "附：旧发布入口退役"
[ ! -e publish.sh ] && [ ! -e deploy.sh ] || die "旧密码式 publish.sh / 旧 deploy.sh 必须删除"
! git ls-files | grep -qE '^(publish|deploy)\.sh$' || die "Git 中仍跟踪旧入口"
# 旧名不得作为身份/命令实际使用；注释里提及旧名（说明清理口径）不算违规——剥掉注释行再扫
legacy=$(git grep -h 'semgravio-openapi' -- deploy/build-bundle.sh deploy/release.sh deploy/server.sh \
    deploy/tests/stub-bin docker-compose.prod.yml Dockerfile .env.production.example \
    | grep -vE '^[[:space:]]*#' || true)
[ -z "$legacy" ] || die "发布链文件仍实际使用旧名 semgravio-openapi：$legacy"
# 注释里声明「不用 sshpass / 不关闭主机密钥校验」不算违规——剥掉注释行再扫
bad_ssh=$(grep -hE 'sshpass|StrictHostKeyChecking' deploy/build-bundle.sh deploy/release.sh deploy/server.sh \
  | grep -vE '^[[:space:]]*#' || true)
[ -z "$bad_ssh" ] || die "发布脚本仍使用 sshpass 或动主机密钥校验：$bad_ssh"
ok "publish.sh/deploy.sh 已删除；发布链（含运维入口）无旧名、无 sshpass、保留主机密钥校验"

printf '\nrelease 主链验收全部通过 ✅（部署包/脏工作区/Compose/Redis/门禁/健康/首次发布自动准备 env/发布前备份/凭证反馈）\n'
