# 测试共用骨架（release-chain.sh / server-ops.sh 共用）：输出助手 + chmod 权限位探测 +
# ssh/scp/docker 替身的一次性虚拟家目录与场景变量。调用方须先定义 TMP_BASE 与 ROOT
#（仓库根，负责 trap 清理与定位 stub-bin）。
# 导出项经 release.sh / server.sh 子进程链传递给替身：
#   STUB_HOME       虚拟家目录（替身 ssh 在此执行「远端」脚本；scp 上传落点）
#   STUB_DOCKER_LOG docker 替身调用记录（断言 docker 行为的唯一口径）
#   STUB_SSH_LOG    ssh 替身调用记录（断言 SSH 参数/远程命令形态）
#   STUB_KEY        替身私钥文件（release/server 的 SSH_KEY 覆盖值）
ok()   { printf '  ✓ %s\n' "$*"; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }
phase(){ printf '\n== %s ==\n' "$*"; }

# chmod 权限位探测：Git Bash（noacl 挂载）下 chmod 为建议性、stat 恒报 644——
# 本机替身环境跳过 600 位断言（CHMOD_EFFECTIVE=0）；Linux 服务器/CI 上照常断言
# （脚本始终 chmod 600 落盘）
chmod_probe=$(mktemp); chmod 600 "$chmod_probe" 2>/dev/null || true
if [ "$(stat -c '%a' "$chmod_probe" 2>/dev/null || echo '?')" = "600" ]; then CHMOD_EFFECTIVE=1; else CHMOD_EFFECTIVE=0; fi
rm -f "$chmod_probe"

new_stub_home() {
  local home
  home=$(mktemp -d "$TMP_BASE/stub-home.XXXXXX")
  : > "$home/docker.log"
  : > "$home/ssh.log"
  touch "$home/id_key"
  STUB_HOME="$home"
  STUB_DOCKER_LOG="$home/docker.log"
  STUB_SSH_LOG="$home/ssh.log"
  STUB_KEY="$home/id_key"
  export STUB_HOME STUB_DOCKER_LOG STUB_SSH_LOG STUB_KEY   # 替身经子进程链取用，必须导出
}
