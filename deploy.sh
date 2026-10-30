#!/bin/bash
set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 步骤 1：检查 .env.production
if [ ! -f .env.production ]; then
  error "找不到 .env.production 文件"
  exit 1
fi
info "环境配置文件检查通过"

# 步骤 2：检查 JAR 文件
if [ ! -f "target/semgravio-app-registry-1.0.0-SNAPSHOT.jar" ]; then
  error "找不到 JAR 文件: target/semgravio-app-registry-1.0.0-SNAPSHOT.jar"
  exit 1
fi
info "JAR 文件检查通过"

# 步骤 3：停止旧容器
docker compose -f docker-compose.prod.yml down 2>/dev/null || true
info "旧容器已停止"

# 步骤 4：构建镜像
info "正在构建 Docker 镜像..."
docker compose -f docker-compose.prod.yml build
info "镜像构建完成"

# 步骤 5：启动容器
info "正在启动容器..."
docker compose -f docker-compose.prod.yml up -d
info "容器已启动"

# 步骤 6：等待健康检查
info "正在等待健康检查..."
MAX_WAIT=30
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT ]; do
  STATUS=$(docker inspect --format='{{.State.Health.Status}}' $(docker compose -f docker-compose.prod.yml ps -q) 2>/dev/null || echo "unknown")
  if [ "$STATUS" = "healthy" ]; then
    break
  fi
  sleep 1
  ELAPSED=$((ELAPSED + 1))
done

# 步骤 7：输出结果
if [ "$STATUS" = "healthy" ]; then
  info "部署成功！容器状态: healthy"
else
  warn "健康检查超时（${MAX_WAIT}s），当前状态: $STATUS"
  warn "请手动检查: docker compose -f docker-compose.prod.yml logs"
fi

# 步骤 8：清理悬空镜像
docker image prune -f >/dev/null 2>&1 || true
