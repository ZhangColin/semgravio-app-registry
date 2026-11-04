#!/usr/bin/env bash
# release 主链命令替身验收（issue #27 AC14）——不连接真实生产服务器：
# ssh/scp/docker 全部走本目录 stub-bin/ 替身，「服务器」行为由 STUB_* 场景变量控制，
# 远端脚本在一次性 $STUB_HOME 里本地执行。断言只看外部行为：退出码、终端输出、
# 部署包内容、Compose 渲染配置——不断言脚本内部实现。
#
# 覆盖：① 部署包（jar/REVISION/无 env 无 secret/LF）② 脏工作区门禁 ③ Compose 无端口暴露
#       ④ Redis requirepass 契约 ⑤ SSH/infra/webnet/env 门禁 ⑥ 健康失败非零 + 成功路径。
#
# 注意：① 打包用真 Maven 构建且要求干净工作区（部署包来自已提交源码），
#       ⑤⑥ 复用①/② 产出的 dist/ 包（SKIP_BUILD=1）。本脚本所有临时文件 trap 清理，
#       中途失败不会弄脏工作区。
# 用法（仓库根）：bash deploy/tests/release-chain.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
STUB_BIN="$ROOT/deploy/tests/stub-bin"
JAR_NAME="semgravio-app-registry-1.0.0-SNAPSHOT.jar"

ok()   { printf '  ✓ %s\n' "$*"; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }
phase(){ printf '\n== %s ==\n' "$*"; }

TMP_BASE=$(mktemp -d)
cleanup() {
  rm -rf "$TMP_BASE"
  rm -f "$ROOT/.rehearsal-dirty-probe"
  [ "${CREATED_ENV:-0}" = 1 ] && rm -f "$ROOT/.env.production"
  docker rm -f ar-rehearsal-redis >/dev/null 2>&1 || true
}
trap cleanup EXIT

new_stub_home() { # 每个场景独立虚拟家目录 + docker 调用日志 + 密钥
  local home
  home=$(mktemp -d "$TMP_BASE/stub-home.XXXXXX")
  : > "$home/docker.log"
  touch "$home/id_key"
  STUB_HOME="$home"
  STUB_DOCKER_LOG="$home/docker.log"
  STUB_KEY="$home/id_key"
  export STUB_HOME STUB_DOCKER_LOG STUB_KEY   # 替身经 release.sh 子进程链取用，必须导出
}

# ---------------------------------------------------------------- ① 打包（真构建）
phase "1/6 部署包（Maven 真构建，须干净工作区）"
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
grep -qE '(^|/)\.env\.production\.example$' <<<"$entries" || die "包内缺 env 模板"
leak=$(grep -Ei '(^|/)(\.env\.production|\.env\.local)$|\.pem$|id_rsa' <<<"$entries" || true)
[ -z "$leak" ] || die "包内出现 env/secret 形态文件：$leak"
ok "包内容：jar + Dockerfile + compose + 发布脚本 + env 模板 + REVISION；无 .env*/密钥"

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
phase "2/6 脏工作区门禁（ALLOW_DIRTY 逃生开关）"
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
phase "3/6 Compose 外部行为（render 断言：内网 only）"
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
phase "4/6 Redis requirepass 契约"
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
phase "5/6 SSH/infra/webnet/env 门禁（应用变更前非零退出）"
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
[ "$rc" -ne 0 ] || die "缺生产 env 场景应非零退出"
grep -q ".env.production" <<<"$out" || die "未提示缺 env：$out"
no_compose_called || die "缺 env 失败后仍动了应用"
ok "服务器无 .env.production → 非零退出（首次 env 准备是 #28，不静默覆盖）"

new_stub_home
mkdir -p "$STUB_HOME/semgravio-app-registry"
printf 'SPRING_DATASOURCE_URL=x\nSPRING_DATASOURCE_USERNAME=x\nSPRING_DATASOURCE_PASSWORD=x\n' \
  > "$STUB_HOME/semgravio-app-registry/.env.production"
out=$(run_release 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "env 缺 REDIS_PASSWORD 场景应非零退出"
grep -q "REDIS_PASSWORD" <<<"$out" || die "未提示缺 REDIS_PASSWORD：$out"
no_compose_called || die "env 缺密码失败后仍动了应用"
ok "env 缺 REDIS_PASSWORD → 非零退出（共享 Redis 已 requirepass）"

# ---------------------------------------------------------------- ⑥ 健康验收 + 成功路径
phase "6/6 健康验收（失败非零 + 日志入口）与成功路径"

new_stub_home
mkdir -p "$STUB_HOME/semgravio-app-registry"
printf 'REDIS_PASSWORD=x\nAPP_REGISTRY_API_SECRET_MASTER_KEY=x\n' \
  > "$STUB_HOME/semgravio-app-registry/.env.production"
out=$(STUB_HEALTH_FAIL=1 HEALTH_WAIT_SECONDS=4 run_release 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "健康超时场景应非零退出（不得只警告报成功）"
grep -q "docker logs" <<<"$out" || die "健康失败未提示日志入口：$out"
grep -q '^compose' "$STUB_DOCKER_LOG" || die "健康失败场景 compose 未被调用（替身/脚本逻辑劈叉）"
ok "健康验收失败 → 非零退出 + 日志入口提示"

new_stub_home
mkdir -p "$STUB_HOME/semgravio-app-registry"
printf 'REDIS_PASSWORD=x\nAPP_REGISTRY_API_SECRET_MASTER_KEY=x\n' \
  > "$STUB_HOME/semgravio-app-registry/.env.production"
out=$(run_release 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "全绿场景应零退出"; }
grep -q "REVISION:" <<<"$out" || die "成功输出缺 REVISION：$out"
grep -q "semgravio-app-registry healthy" <<<"$out" || die "成功输出缺健康验收：$out"
grep -q "已上线" <<<"$out" || die "成功输出缺完成提示：$out"
grep -q 'compose -f docker-compose.prod.yml up -d --build' "$STUB_DOCKER_LOG" \
  || die "compose 未按 up -d --build 调用"
! grep -q ' down ' "$STUB_DOCKER_LOG" || die "不得先 down 旧容器"
[ -f "$STUB_HOME/semgravio-app-registry/REVISION" ] || die "成功后未解包"
ok "成功路径：解包 → compose up -d --build（不先 down）→ 健康验收 → 完成"

# ---------------------------------------------------------------- 旧入口退役
phase "附：旧发布入口退役"
[ ! -e publish.sh ] && [ ! -e deploy.sh ] || die "旧密码式 publish.sh / 旧 deploy.sh 必须删除"
! git ls-files | grep -qE '^(publish|deploy)\.sh$' || die "Git 中仍跟踪旧入口"
! git grep -q 'semgravio-openapi' -- deploy/build-bundle.sh deploy/release.sh deploy/tests/stub-bin \
    docker-compose.prod.yml Dockerfile .env.production.example \
  || die "发布链文件仍出现旧名 semgravio-openapi"
! grep -rEq 'sshpass|StrictHostKeyChecking=no' deploy --include='*.sh' \
  || die "发布链仍使用 sshpass 或关闭主机密钥校验"
ok "publish.sh/deploy.sh 已删除；发布链无旧名、无 sshpass、保留主机密钥校验"

printf '\nrelease 主链验收全部通过 ✅（部署包/脏工作区/Compose/Redis/门禁/健康）\n'
