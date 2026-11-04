#!/usr/bin/env bash
# server.sh 运维入口外部行为验收（issue #29）——不连接真实生产服务器：ssh/docker 全部走
# 本目录 stub-bin/ 替身，「远端」命令在一次性 $STUB_HOME 里本地执行。断言只看外部行为：
# 退出码、终端输出、docker 调用记录（$STUB_DOCKER_LOG）、ssh 调用参数（$STUB_SSH_LOG）。
#
# 覆盖：① 帮助文本（各子命令；不得把备份描述成回滚）② 缺密钥非零 ③ status ④ logs 映射
#       ⑤ restart 只动本服务 ⑥ dump（同发布前备份边界/凭据/时间戳/权限/失败清理/无 env/
#       REMOTE_DIR 覆盖）⑦ smoke（内部 actuator，不碰 bootstrap 端点）⑧ sh 交互形态
#       ⑨ SSH 安全参数横切（BatchMode+ConnectTimeout+指定密钥，无 sshpass/不关主机密钥校验）。
# 用法（仓库根）：bash deploy/tests/server-ops.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
STUB_BIN="$ROOT/deploy/tests/stub-bin"

TMP_BASE=$(mktemp -d)
cleanup() { rm -rf "$TMP_BASE"; }
trap cleanup EXIT

. "$ROOT/deploy/tests/lib.sh"   # ok/die/phase + CHMOD_EFFECTIVE + new_stub_home

run_ops() { # 场景 env 由调用方 export；统一覆盖连接参数（与 release 同套默认、同法覆盖）
  PATH="$STUB_BIN:$PATH" \
  SERVER_HOST=stub SERVER_USER=stub SSH_KEY="$STUB_KEY" \
  REMOTE_DIR=semgravio-app-registry bash deploy/server.sh "$@"
}

# 残缺备份不得顶着正式名号留存：既无 *.sql.gz 也无 *.tmp 残骸
no_backup_left() {
  local d="$STUB_HOME/semgravio-app-registry/backups"
  [ ! -e "$d" ] && return 0
  [ -z "$(find "$d" \( -name '*.sql.gz' -o -name '*.tmp' \) -print -quit 2>/dev/null)" ]
}

# ---------------------------------------------------------------- ① 帮助文本
phase "1/9 帮助文本（子命令清单；备份≠回滚）"
[ -f deploy/server.sh ] || die "缺 deploy/server.sh"
for invocation in "" "help"; do
  help_out=$(bash deploy/server.sh $invocation 2>&1); rc=$?
  [ "$rc" -eq 0 ] || die "help（'$invocation'）应零退出：rc=$rc"
  for kw in status logs restart dump smoke sh; do
    grep -q "$kw" <<<"$help_out" || die "help 缺子命令说明：$kw"
  done
done
# AC：文档/帮助不得把数据库备份描述为自动回滚——剥掉「不是/而非/恢复材料」式澄清后不得再提回滚
rollback_claim=$( { grep -E '回滚' <<<"$help_out"; grep -E '回滚' deploy/server.sh deploy/release.sh; } \
                  | grep -vE '不是|而非|≠|恢复材料' || true)
[ -z "$rollback_claim" ] || die "帮助/脚本注释把备份描述成回滚：$rollback_claim"
grep -q '恢复材料' <<<"$help_out" || die "help 未说明备份定位（恢复材料）"
ok "help/无参列出六子命令；备份定位为恢复材料，无回滚话术"

# ---------------------------------------------------------------- ② 缺密钥
phase "2/9 缺密钥非零退出"
new_stub_home
out=$(SSH_KEY="$STUB_HOME/none.pem" PATH="$STUB_BIN:$PATH" \
      SERVER_HOST=stub SERVER_USER=stub bash deploy/server.sh status 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "缺密钥的 status 竟然成功"
grep -q "密钥" <<<"$out" || die "缺密钥未提示密钥（可用 SSH_KEY=... 覆盖）：$out"
[ ! -s "$STUB_DOCKER_LOG" ] || die "缺密钥不应已发出 docker 调用"
ok "SSH 私钥缺失 → 非零退出＋提示，未触达服务器"

# ---------------------------------------------------------------- ③ status
phase "3/9 status（容器概览）"
new_stub_home
out=$(run_ops status 2>&1); rc=$?
[ "$rc" -eq 0 ] || die "status 应零退出：rc=$rc，$out"
for c in semgravio-app-registry semgravio-pg semgravio-redis semgravio-nginx; do
  grep -q "$c" <<<"$out" || die "status 输出缺容器 $c：$out"
done
! grep -q 'unrelated-app' <<<"$out" || die "status 把无关容器也列出来了：$out"
grep -q '^ps ' "$STUB_DOCKER_LOG" || die "status 未走 docker ps"
ok "status：本服务＋共享 infra 一览，无关容器被过滤"

# ---------------------------------------------------------------- ④ logs
phase "4/9 logs（svc 映射，缺省 app）"
new_stub_home
out=$(run_ops logs 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "logs 缺省应零退出"; }
grep -q '^logs -f --tail 200 semgravio-app-registry$' "$STUB_DOCKER_LOG" \
  || die "logs 缺省未跟 semgravio-app-registry：$(cat "$STUB_DOCKER_LOG")"
grep -q 'stub-log-line-1' <<<"$out" || die "logs 未回显日志行：$out"
for pair in "pg semgravio-pg" "redis semgravio-redis" "nginx semgravio-nginx"; do
  set -- $pair
  new_stub_home
  run_ops logs "$1" >/dev/null 2>&1 || die "logs $1 应零退出"
  grep -q "^logs -f --tail 200 $2\$" "$STUB_DOCKER_LOG" || die "logs $1 应跟 $2"
done
ok "logs：缺省 app；pg/redis/nginx 分别映射到对应容器"

# ---------------------------------------------------------------- ⑤ restart
phase "5/9 restart（只重启本服务）"
new_stub_home
out=$(run_ops restart 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "restart 应零退出"; }
grep -q "已重启" <<<"$out" || die "restart 缺完成提示：$out"
restart_args=$(grep '^restart' "$STUB_DOCKER_LOG")
[ "$restart_args" = "restart semgravio-app-registry" ] \
  || die "restart 必须只重启 semgravio-app-registry（不动共享 infra）：$restart_args"
ok "restart：仅 semgravio-app-registry，pg/redis/nginx 不被触碰"

# ---------------------------------------------------------------- ⑥ dump
phase "6/9 dump（与发布前备份同边界）"

write_ops_env() { # $1=env 路径（dump 凭据正本：本服务自己的库密码）
  cat > "$1" <<'EOF'
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://semgravio-pg:5432/semgravio_app_registry
SPRING_DATASOURCE_USERNAME=semgravio_app_registry
SPRING_DATASOURCE_PASSWORD=ops-dump-pw-1
REDIS_HOST=semgravio-redis
REDIS_PORT=6379
REDIS_PASSWORD=ops-redis-pw
APP_REGISTRY_API_SECRET_MASTER_KEY=ops-master-key
EOF
}

new_stub_home
mkdir -p "$STUB_HOME/semgravio-app-registry"
write_ops_env "$STUB_HOME/semgravio-app-registry/.env.production"
out=$(run_ops dump 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "dump 应零退出"; }
BK_DIR="$STUB_HOME/semgravio-app-registry/backups"
bk_file=$(ls "$BK_DIR"/semgravio_app_registry-*.sql.gz 2>/dev/null | head -1)
[ -n "$bk_file" ] || die "dump 未产出备份文件（$BK_DIR）：$out"
basename "$bk_file" | grep -Eq '^semgravio_app_registry-[0-9]{8}-[0-9]{6}\.sql\.gz$' \
  || die "备份文件名缺时间戳：$(basename "$bk_file")"
grep -q '^backups/semgravio_app_registry-[0-9]\{8\}-[0-9]\{6\}\.sql\.gz$' <<<"$out" \
  || die "dump 输出应回显备份路径：$out"
gunzip -c "$bk_file" | grep -q 'PostgreSQL database dump complete' || die "备份内容不完整（缺 complete 收尾）"
if [ "$CHMOD_EFFECTIVE" = "1" ]; then
  [ "$(stat -c '%a' "$bk_file")" = "600" ] || die "备份权限非 600：$(stat -c '%a' "$bk_file")"
fi
[ -z "$(find "$BK_DIR" -name '*.tmp' -print -quit)" ] || die "dump 留下 .tmp 残骸"
# 数据库边界：本服务独立库＋同名低权限角色＋自己的密码（env 正本），不碰 aiplatform/超管
grep -q -- '-e PGPASSWORD=ops-dump-pw-1 semgravio-pg pg_dump -U semgravio_app_registry -d semgravio_app_registry' \
  "$STUB_DOCKER_LOG" || die "dump 边界不对：$(grep pg_dump "$STUB_DOCKER_LOG")"
! grep -Eq 'pg_dump.*aiplatform|POSTGRES_PASSWORD' "$STUB_DOCKER_LOG" \
  || die "dump 触碰了别的库/超管凭据：$(grep pg_dump "$STUB_DOCKER_LOG")"
ok "dump：semgravio_app_registry 库+同名角色+env 密码；时间戳名/完整正本/600/无残骸"

# 同一秒内再次 dump（如发布自动备份后立即手工 dump）：同名不得覆盖，留存两份
run_ops dump >/dev/null 2>&1 || die "第二次 dump 应零退出"
n_bk=$(ls "$BK_DIR"/semgravio_app_registry-*.sql.gz 2>/dev/null | wc -l)
[ "$n_bk" -eq 2 ] || die "两次 dump 应留存两份备份（同秒同名不得覆盖，实际 $n_bk 份）"
ok "同一秒连续两次 dump → 两份均留存（碰撞追加序号，不覆盖旧正本）"

new_stub_home
mkdir -p "$STUB_HOME/semgravio-app-registry"
write_ops_env "$STUB_HOME/semgravio-app-registry/.env.production"
out=$(STUB_DUMP_FAIL=1 run_ops dump 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "dump 失败场景应非零退出"
grep -q "备份失败" <<<"$out" || die "dump 失败未明确报错：$out"
no_backup_left || die "dump 失败留下残缺备份/临时文件"
ok "pg_dump 中途失败 → 非零退出，无残缺文件留存"

new_stub_home
mkdir -p "$STUB_HOME/semgravio-app-registry"
out=$(run_ops dump 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "无 env 场景 dump 应非零退出"
grep -q "发布" <<<"$out" || die "无 env 应提示先完成发布：$out"
! grep -q 'pg_dump' "$STUB_DOCKER_LOG" || die "无 env 不应已跑 pg_dump"
ok "应用目录无 .env.production → 非零退出＋提示先发布"

new_stub_home
mkdir -p "$STUB_HOME/custom-app-dir"
write_ops_env "$STUB_HOME/custom-app-dir/.env.production"
out=$(REMOTE_DIR=custom-app-dir PATH="$STUB_BIN:$PATH" SERVER_HOST=stub SERVER_USER=stub \
      SSH_KEY="$STUB_KEY" bash deploy/server.sh dump 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "REMOTE_DIR 覆盖的 dump 应零退出"; }
[ -n "$(ls "$STUB_HOME/custom-app-dir"/backups/semgravio_app_registry-*.sql.gz 2>/dev/null)" ] \
  || die "REMOTE_DIR 覆盖后备份未落在 custom-app-dir/backups"
ok "REMOTE_DIR=custom-app-dir 覆盖生效（备份随应用目录）"

# ---------------------------------------------------------------- ⑦ smoke
phase "7/9 smoke（内部 actuator 健康，不碰 bootstrap 端点）"
new_stub_home
out=$(run_ops smoke 2>&1); rc=$?
[ "$rc" -eq 0 ] || { echo "$out" >&2; die "smoke 应零退出"; }
grep -q '"status":"UP"' <<<"$out" || die "smoke 输出缺健康体：$out"
smoke_url=$(grep '^exec ' "$STUB_DOCKER_LOG" | grep curl | tail -1)
grep -q 'http://localhost:8088/actuator/health' <<<"$smoke_url" \
  || die "smoke 应打容器内 actuator/health：$smoke_url"
! grep -Eq 'api-keys|bootstrap' <<<"$smoke_url" \
  || die "smoke 不得访问返回明文 apiSecret 的 bootstrap 端点：$smoke_url"
ok "smoke：容器内 actuator/health，未触 bootstrap 端点"

new_stub_home
out=$(STUB_HEALTH_FAIL=1 run_ops smoke 2>&1); rc=$?
[ "$rc" -ne 0 ] || die "健康不可达时 smoke 应非零退出"
ok "actuator 不可达 → smoke 非零退出"

# ---------------------------------------------------------------- ⑧ sh
phase "8/9 sh（交互登录形态）"
new_stub_home
run_ops sh </dev/null >/dev/null 2>&1 || die "sh 应零退出（替身连通即成功）"
ssh_args=$(tail -n 1 "$STUB_SSH_LOG")
grep -Eq -- "-o BatchMode=yes -o ConnectTimeout=15 stub@stub$" <<<"$ssh_args" \
  || die "sh 应为无远程命令的交互形态（参数尾即 host）：$ssh_args"
ok "sh：exec ssh 直连（无远程命令串）"

# ---------------------------------------------------------------- ⑨ SSH 安全参数
phase "9/9 SSH 安全参数横切（沿用 release）"
new_stub_home
run_ops status >/dev/null 2>&1 || die "status 应零退出"
run_ops restart >/dev/null 2>&1 || die "restart 应零退出"
bad_ssh=$(grep -vE -- "^-i .* -o BatchMode=yes -o ConnectTimeout=15 stub@stub( .*)?$" "$STUB_SSH_LOG" || true)
[ -z "$bad_ssh" ] || die "存在不带「密钥+BatchMode+ConnectTimeout」的 ssh 调用：$bad_ssh"
grep -qF -- "-i $STUB_KEY " "$STUB_SSH_LOG" || die "ssh 未使用覆盖指定的密钥 $STUB_KEY"
# 注释里声明「不用 sshpass / 不关闭主机密钥校验」不算违规——剥掉注释行再扫（与 release-chain 同口径）
bad_ops_ssh=$(grep -hE 'sshpass|StrictHostKeyChecking' deploy/server.sh | grep -vE '^[[:space:]]*#' || true)
[ -z "$bad_ops_ssh" ] || die "运维脚本使用 sshpass 或动主机密钥校验：$bad_ops_ssh"
ok "所有 ssh 调用：指定密钥 + BatchMode + ConnectTimeout，主机密钥校验保留"

printf '\nserver 运维入口验收全部通过 ✅（status/logs/restart/dump/smoke/sh/安全参数）\n'
