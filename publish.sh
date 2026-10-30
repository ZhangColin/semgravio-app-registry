#!/bin/bash
set -e

# ========== 配置区 ==========
SERVER_USER="root"
SERVER_HOST="43.140.211.9"
SERVER_PATH="/opt/hcy/semgravio-openapi"
SERVER_PASSWORD="Hcy@20260327"

# ========== 逻辑区 ==========

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

SSH_CMD="sshpass -p $SERVER_PASSWORD ssh -o StrictHostKeyChecking=no"
RSYNC_CMD="sshpass -p $SERVER_PASSWORD rsync"

# 1. 本地构建
info "正在构建项目..."
mvn package -DskipTests -q

# 2. 检查 JAR 文件
if [ ! -f "target/semgravio-app-registry-1.0.0-SNAPSHOT.jar" ]; then
  error "构建失败：找不到 target/semgravio-app-registry-1.0.0-SNAPSHOT.jar"
  exit 1
fi
info "构建完成"

# 3. 检查 sshpass
if ! command -v sshpass &>/dev/null; then
  error "sshpass 未安装"
  echo "请执行: brew install hudochenkov/sshpass/sshpass"
  exit 1
fi

# 4. 创建临时目录，只包含需要的文件
TMP_DIR=$(mktemp -d)
info "准备部署文件..."
mkdir -p "$TMP_DIR/target"
cp "target/semgravio-app-registry-1.0.0-SNAPSHOT.jar" "$TMP_DIR/target/"
cp Dockerfile "$TMP_DIR/"
cp docker-compose.prod.yml "$TMP_DIR/"
cp deploy.sh "$TMP_DIR/"
cp .env.production "$TMP_DIR/"

# 5. rsync 到服务器
info "正在上传文件到服务器..."
$SSH_CMD ${SERVER_USER}@${SERVER_HOST} "mkdir -p ${SERVER_PATH}/target"
$RSYNC_CMD -avz -e "ssh -o StrictHostKeyChecking=no" "$TMP_DIR/" ${SERVER_USER}@${SERVER_HOST}:${SERVER_PATH}/

# 6. SSH 执行部署
info "正在远程部署..."
$SSH_CMD ${SERVER_USER}@${SERVER_HOST} "cd ${SERVER_PATH} && chmod +x deploy.sh && bash deploy.sh"

# 7. 清理临时目录
rm -rf "$TMP_DIR"
info "部署完成！临时文件已清理"
