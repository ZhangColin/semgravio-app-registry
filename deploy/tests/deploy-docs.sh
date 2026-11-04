#!/usr/bin/env bash
# 部署文档守护验收（issue #31）——deploy/部署文档.md 是维护者的唯一操作正本，本脚本守住
# 两类漂移：① 口径漂移（AC 明确要求的说法/命令/开关在文档里说清）② 端点漂移（文档端点
# 清单与控制器源码逐一对应：方法、路径、签名要求）。文档措辞可以自由改写，但关键口径的
# 固定短语与端点表格必须与源码/脚本同步——改端点或改发布参数时，本测试会拽你回来更新文档。
#
# 不覆盖：端点的业务语义/错误码（#25 已有源码侧守护）；文档的叙述质量（人工评审）。
# 用法（仓库根）：bash deploy/tests/deploy-docs.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
DOC="deploy/部署文档.md"
CTRL_DIR="src/main/java/com/semgravio/appregistry/endpoints/controller"

TMP_BASE=$(mktemp -d)
cleanup() { rm -rf "$TMP_BASE"; }
trap cleanup EXIT

. "$ROOT/deploy/tests/lib.sh"   # ok/die/phase

# ---------------------------------------------------------------- ① 在场与口径覆盖
phase "1/3 文档在场 + AC 口径覆盖（固定短语）"
[ -f "$DOC" ] || die "缺 $DOC（部署文档是 #31 的交付物）"

# 章节主题（AC：前置条件、首次发布、重复发布、参数覆盖、凭证接收、备份、运维、故障排查）
for kw in 前置条件 首次发布 重复发布 参数覆盖 备份 运维 故障排查; do
  grep -q "$kw" "$DOC" || die "文档缺主题：$kw"
done
ok "章节主题齐备（前置条件/首次/重复发布/参数覆盖/备份/运维/故障排查）"

# 初始化单入口：启动前配置由 release 首次发布自动准备；应用内初始化只有 Flyway + 平台 seed
grep -qF '没有第二个初始化入口' "$DOC" || die "文档未声明「没有第二个初始化入口」"
grep -q 'Flyway' "$DOC" || die "文档未提 Flyway（应用内初始化单入口之一）"
grep -q '平台 seed' "$DOC" || die "文档未提平台 seed（应用内初始化单入口之一）"
ok "初始化模型：首次发布自动准备启动前配置；Flyway + 平台 seed 是唯一应用内初始化"

# master key：稳定保存 + 离线备份责任；数据库备份不含 master key
for s in '终身不变' '密码管理器' '离线备份' '数据库备份不含 master key'; do
  grep -qF "$s" "$DOC" || die "文档缺 master key 口径：$s"
done
ok "master key：终身不变 + 密码管理器/离线备份 + 数据库备份不含 master key"

# 内部调用契约
grep -qF 'http://semgravio-app-registry:8088' "$DOC" || die "文档未给出内部 base URL（webnet 容器 DNS）"
grep 'localhost' "$DOC" | grep -qF '指向调用方容器自身' || die "文档未解释 localhost 指向调用方容器自身"
grep -qF '不能直接使用容器 DNS' "$DOC" || die "文档未说明宿主机进程/webnet 外容器不能用容器 DNS"
grep -qF '无需服务器 IP 或宿主端口' "$DOC" || die "文档未说明无需服务器 IP 或宿主端口"
ok "内部调用契约：base URL / localhost / 容器 DNS 边界 / 无需 IP 与宿主端口"

# 网络边界现状与未来
for s in '无宿主端口' '无 nginx 公网路由' '无需 SSL' '另行设计' '不能直接公开'; do
  grep -qF "$s" "$DOC" || die "文档缺网络边界口径：$s"
done
ok "网络边界：无宿主端口/无 nginx 公网路由/无需 SSL；外部调用须另行设计、不公开匿名 bootstrap"

# 备份 ≠ 回滚（措辞红线在 ③ 统一扫描）；零停机不承诺
grep -qF '备份不等于回滚' "$DOC" || die "文档缺「备份不等于回滚」"
grep -qF '不承诺零停机' "$DOC" || die "文档缺「不承诺零停机」"
ok "备份定位：恢复材料而非回滚；不承诺零停机"

# admin-console 凭证接收 + 日志仍保留时的再查命令
grep -q 'admin-console' "$DOC" || die "文档未提 admin-console 凭证接收"
grep -q 'apiSecret' "$DOC" || die "文档未提 apiSecret"
grep -qF "grep 'admin-console seed'" "$DOC" || die "文档缺 seed 凭证事件的再查命令（docker logs | grep 'admin-console seed'）"
ok "凭证接收：admin-console apiSecret 反馈 + 日志留存期的再查命令"

# 参数覆盖与常用开关（与 aiplatform 同一套；与 release.sh 头注释逐名对应）
for v in SERVER_HOST SERVER_USER SSH_KEY REMOTE_DIR INFRA_DIR HEALTH_WAIT_SECONDS \
         SKIP_BUILD ALLOW_DIRTY SKIP_BACKUP; do
  grep -q "$v" "$DOC" || die "文档缺参数/开关说明：$v"
done
ok "参数覆盖 + 常用开关（SERVER_*/REMOTE_DIR/INFRA_DIR/HEALTH_WAIT + SKIP_BUILD/ALLOW_DIRTY/SKIP_BACKUP）"

# 四个入口与运维子命令（aiplatform 使用习惯：仓库根执行）
for e in deploy/build-bundle.sh deploy/release.sh deploy/server.sh deploy/rehearsal.sh; do
  grep -qF "$e" "$DOC" || die "文档缺入口：$e"
done
for c in status logs restart dump smoke; do
  grep -q "$c" "$DOC" || die "文档缺运维子命令说明：$c"
done
ok "入口齐备：build-bundle/release/server/rehearsal + 运维子命令表"

# ---------------------------------------------------------------- ② 端点清单与源码一致
phase "2/3 端点清单与控制器源码一致（方法/路径/签名要求）"
# 从控制器源码抽取端点：类级 @RequestMapping 基座 + 方法级 mapping 组合。
# 签名要求按控制器文件判定：文件含 @NoSignature → 无验签（仅 SignatureBootstrapController，
# 单端点）；否则该控制器全部端点需验签（类级 @RequireSignature）。注解挪位置时此判定会失真，
# 测试随之变红、拽人核对——可接受的守护成本。
declare -a EP_METHODS=() EP_PATHS=() EP_SIGS=()
for f in "$CTRL_DIR"/*.java; do
  base=$(sed -n 's/.*@RequestMapping("\([^"]*\)").*/\1/p' "$f" | head -n 1)
  [ -n "$base" ] || continue
  if grep -q '@NoSignature' "$f"; then sig='无验签'; else sig='需验签'; fi
  while IFS= read -r m; do
    [ -n "$m" ] || continue
    method=$(printf '%s' "${m%% *}" | tr '[:lower:]' '[:upper:]')
    sub=${m#* }
    EP_METHODS+=("$method")
    EP_PATHS+=("$base$sub")
    EP_SIGS+=("$sig")
  done < <(grep -oE '@(Get|Post|Put|Delete|Patch)Mapping(\("[^"]*"\))?' "$f" \
           | sed -E 's/@([A-Za-z]+)Mapping(\("([^"]*)"\))?/\1 \3/')
done

# 抽取坍塌守卫：端点数对不上（重构/注解变形）即红，防止下面的逐项断言空转变相通过
n_ep=${#EP_METHODS[@]}
[ "$n_ep" -eq 17 ] || die "从控制器抽到 $n_ep 个端点（应为 17）——抽取规则与源码形态劈叉，先修本测试：$(printf '%s %s\n' "${EP_METHODS[@]}" "${EP_PATHS[@]}" | head -40)"

for i in "${!EP_METHODS[@]}"; do
  method=${EP_METHODS[$i]} path=${EP_PATHS[$i]} sig=${EP_SIGS[$i]}
  # 文档端点表里路径以反引号包裹成整格——防 /apps 被 /apps/{id} 的行误顶替
  row=$(grep -F -- "\`$path\`" "$DOC" | grep -w "$method" || true)
  [ -n "$row" ] || die "文档端点表缺：$method \`$path\`"
  <<<"$row" grep -q "$sig" || die "文档对 $method \`$path\` 的签名要求与源码不符（应为 $sig）：$row"
done
ok "端点表 ${n_ep} 项与控制器逐一对应：方法 + 路径 + 签名要求（无验签/需验签）"

# ---------------------------------------------------------------- ③ 话术红线
phase "3/3 话术红线（备份≠回滚；不承诺零停机）"
# 与 server-ops.sh 同口径：提「回滚」的行必须带否定/澄清语，不得把备份描述成自动回滚
rollback_claim=$(grep -E '回滚' "$DOC" | grep -vE '不是|而非|≠|不支持|不等于|恢复材料' || true)
[ -z "$rollback_claim" ] || die "文档把备份/发布描述成回滚（缺否定语境）：$rollback_claim"
ok "回滚话术合规：仅出现在「不支持/不等于」语境"

printf '\n部署文档守护验收全部通过 ✅（口径覆盖 + 端点与源码一致 + 话术红线）\n'
