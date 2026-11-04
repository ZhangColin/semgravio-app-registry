#!/usr/bin/env bash
# 部署面端到端本地排练（issue #31）——不改真实服务器、不动共享 infra：一条命令跑完部署面的
# 全部验收，作为发布链/文档/compose 改动后的回归入口（对应 aiplatform 的 deploy/rehearsal.sh
# 使用习惯；本服务的排练本身就是命令替身形态，且「真 Maven 打包」是验收内容之一，故只有
# 单一模式、无快路径开关）。
#
# 依次执行（任一失败即非零退出，指明失败环节并保留日志现场）：
#   1. 语法检查     bash -n 全部部署脚本（含测试与命令替身）
#   2. LF 约束      部署脚本字节级无 CR（.gitattributes 钉 LF 的运行期复核；Git Bash 下运行
#                   本脚本本身就是 Windows 执行路径的验证）
#   3. 部署文档守护 deploy/tests/deploy-docs.sh（文档口径 + 端点清单与控制器源码一致）
#   4. 发布主链排练 deploy/tests/release-chain.sh（真打包；首次/重复发布、infra/SSH/env 门禁、
#                   master key 校验、Redis requirepass、无端口暴露、备份、健康失败、凭证反馈、
#                   secret 不泄漏——ssh/scp/docker 全走命令替身）
#   5. 运维入口排练 deploy/tests/server-ops.sh（status/logs/restart/dump/smoke/sh + SSH 安全参数）
#
# 前置：工作区须干净（④ 要求部署包来自已提交源码；带脏场景请直接跑单套脚本自行组合
# ALLOW_DIRTY）；不在 main 上。结尾打印各环节实际执行结果与耗时。
# 用法（仓库根）：deploy/rehearsal.sh
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD

ok()   { printf '  ✓ %s\n' "$*"; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }
phase(){ printf '\n== %s ==\n' "$*"; }

LOG_DIR=$(mktemp -d)
ALL_PASS=0
cleanup() { [ "$ALL_PASS" = 1 ] && rm -rf "$LOG_DIR"; }
trap cleanup EXIT

# 各环节结果汇总（名称 + 耗时），收尾统一打印
declare -a SUITE_NAMES=() SUITE_SECS=()

run_suite() { # $1=环节名 $2...=命令——实时透传输出、日志落 $LOG_DIR、失败即中止并留现场
  local name=$1 t0 rc
  shift
  SUITE_NAMES+=("$name")
  t0=$(date +%s)
  set -o pipefail
  "$@" 2>&1 | tee "$LOG_DIR/${name//\//_}.log"
  rc=$?
  SUITE_SECS+=("$(( $(date +%s) - t0 ))")
  [ "$rc" -eq 0 ] || { printf '\n✗ %s 失败（退出码 %s）——完整日志：%s\n' \
      "$name" "$rc" "$LOG_DIR/${name//\//_}.log" >&2; exit "$rc"; }
  ok "$name 通过"
}

# ---------------------------------------------------------------- 0. 前置
phase "0/6 前置检查（工作区/分支/docker）"
[ -z "$(git status --porcelain)" ] \
  || die "工作区不干净——④ 发布主链要求部署包来自已提交源码；先提交，或单独跑 bash deploy/tests/release-chain.sh"
[ "$(git rev-parse --abbrev-ref HEAD)" != "main" ] || die "拒绝在 main 上排练发布"
command -v docker >/dev/null 2>&1 || die "docker 不可用（③ compose render 与 ④ requirepass 契约需要）"
ok "前置通过（分支 $(git rev-parse --abbrev-ref HEAD)，REVISION $(git rev-parse --short HEAD)）"

# 部署脚本全集（语法/LF 检查对象；stub-bin 按命令名命名、无 .sh 后缀，同为脚本）
SCRIPTS=(deploy/build-bundle.sh deploy/release.sh deploy/server.sh deploy/rehearsal.sh)
for f in deploy/tests/*.sh deploy/tests/stub-bin/*; do SCRIPTS+=("$f"); done

# ---------------------------------------------------------------- 1. 语法检查
phase "1/6 语法检查（bash -n × ${#SCRIPTS[@]} 个脚本）"
for f in "${SCRIPTS[@]}"; do
  bash -n "$f" || die "语法错误：$f"
done
ok "${#SCRIPTS[@]} 个部署脚本（含测试与命令替身）语法全部通过"

# ---------------------------------------------------------------- 2. LF 约束
phase "2/6 LF 约束（字节级无 CR）"
# tr 字节级检测（勿改回 grep $'\r'：Git Bash 5.3 下脚本内 $'\r' 异常展开为空串导致误匹配全部
# 通过——release-chain.sh 已踩过）；.gitattributes 钉的 LF 在这里做运行期复核
crlf=$(for f in "${SCRIPTS[@]}"; do
  [ -n "$(LC_ALL=C tr -cd '\r' < "$f")" ] && printf '%s\n' "$f"
done)
[ -z "$crlf" ] || die "部署脚本出现 CRLF（服务器侧 bash 会拒跑）：$crlf"
ok "${#SCRIPTS[@]} 个脚本全部 LF（Windows Git Bash 下运行本排练即执行路径验证）"

# ---------------------------------------------------------------- 3~5. 验收套件
phase "3/6 部署文档守护"
run_suite "deploy-docs" bash deploy/tests/deploy-docs.sh

phase "4/6 发布主链排练（真 Maven 打包，耗时主体）"
run_suite "release-chain" bash deploy/tests/release-chain.sh

phase "5/6 运维入口排练"
run_suite "server-ops" bash deploy/tests/server-ops.sh

# ---------------------------------------------------------------- 6. 汇总
phase "6/6 执行结果汇总"
for i in "${!SUITE_NAMES[@]}"; do
  printf '  ✓ %-14s %ss\n' "${SUITE_NAMES[$i]}" "${SUITE_SECS[$i]}"
done
printf '  · 静态检查        语法 %s 个脚本 + LF %s 个脚本（1/6、2/6 环节内）\n' \
  "${#SCRIPTS[@]}" "${#SCRIPTS[@]}"
ALL_PASS=1
printf '\n部署排练全部通过 ✅（语法/LF + 文档守护 + 发布主链 + 运维入口；未连接真实服务器）\n'
