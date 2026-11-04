#!/usr/bin/env bash
# 组装自包含部署包：dist/semgravio-app-registry-deploy-<时间戳>.tar.gz（macOS + Windows Git Bash）
# 在开发机仓库根执行：deploy/build-bundle.sh；末行打印包路径（release.sh 依赖该约定）。
#
# 产物 = git archive HEAD 全仓正本（恒 LF，含 Dockerfile/compose/发布脚本/env 模板/文档）
#        ＋ 可执行 fat jar（开发机 JDK 21 + Maven 构建——私有 cartisan-boot 依赖只在开发机解析，
#        服务器只做 Docker 镜像构建）＋ REVISION。包内不含 .env*（env 未提交且 archive 只导已跟踪
#        文件）与任何 secret；现网 env 永不被部署包覆盖。
#
# 工作区脏默认拒绝打包（jar 按工作区构建、源码树按 HEAD，两者会不一致）；确认自担风险：
#   ALLOW_DIRTY=1 deploy/build-bundle.sh（警告后按 HEAD 打源码树）
# 复用现有 jar 跳过 Maven：SKIP_MVN=1（配合 ALLOW_DIRTY 做带脏演练时避免重复构建）。
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="target/semgravio-app-registry-1.0.0-SNAPSHOT.jar"

if [ -n "$(git status --porcelain)" ]; then
  if [ "${ALLOW_DIRTY:-0}" = "1" ]; then
    echo "警告：工作区有未提交改动，源码树按 HEAD 打包、jar 按工作区构建，两者可能不一致" >&2
  else
    echo "错误：工作区有未提交改动（如下）。先提交再打包；确认带脏打包用：ALLOW_DIRTY=1 deploy/build-bundle.sh" >&2
    git status --porcelain >&2
    exit 1
  fi
fi

STAMP=$(date +%Y%m%d-%H%M)
OUT="dist/semgravio-app-registry-deploy-${STAMP}"

echo "== 1/4 构建 fat jar（JDK 21 + Maven，-DskipTests；实现验收的测试须独立跑 mvn test） =="
if [ "${SKIP_MVN:-0}" = "1" ]; then
  [ -f "$JAR" ] || { echo "错误：$JAR 不存在，无法 SKIP_MVN"; exit 1; }
  echo "跳过 Maven 构建，复用现有 $JAR"
else
  mvn clean package -DskipTests -q
fi
[ -f "$JAR" ] || { echo "错误：构建后仍找不到 $JAR"; exit 1; }

echo "== 2/4 组装目录（git archive，仓库正本恒 LF） =="
rm -rf "${OUT}"    # 同分钟内重跑兜底
mkdir -p "${OUT}/target"
# git archive 会按本机 core.autocrlf 做 checkout 同款转换（Windows 上＝CRLF，实测踩过），
# 覆盖后输出仓库正本、恒 LF。全仓导出：部署包来自已提交源码，与 REVISION 严格一致。
git -c core.autocrlf=false -c core.eol=lf archive HEAD | tar -x -C "${OUT}"
cp "$JAR" "${OUT}/target/"

echo "== 3/4 记录 revision =="
git rev-parse HEAD > "${OUT}/REVISION"

echo "== 4/4 打包 =="
tar -C dist -czf "${OUT}.tar.gz" "semgravio-app-registry-deploy-${STAMP}"
rm -rf "${OUT}"
du -sh "${OUT}.tar.gz"
echo "完成，包路径："
echo "${OUT}.tar.gz"
