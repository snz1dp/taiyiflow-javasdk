.PHONY: test build worker-package check-container-cli image docker package publish clean docs-init docs-dev docs-build docs-build-gh docs-preview docs-image docs-run docs-stop

ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
MVNW := $(ROOT_DIR)/mvnw
IMAGE ?= taiyiflow-worker:local
BUILD_IMAGE ?= docker.m.daocloud.io/library/eclipse-temurin:21-jdk
RUNTIME_IMAGE ?= docker.m.daocloud.io/library/eclipse-temurin:21-jre
DOCKER_PLATFORM ?=
DOCKER ?= $(shell command -v docker 2>/dev/null || command -v podman 2>/dev/null || command -v nerdctl 2>/dev/null)

test:
	"$(MVNW)" -B -ntp test

build:
	"$(MVNW)" -B -ntp clean package

worker-package:
	"$(MVNW)" -B -ntp -pl taiyiflow-sdk-worker -am clean package

check-container-cli:
	@if [ -z "$(DOCKER)" ]; then \
		echo "No docker, podman, or nerdctl executable found; set DOCKER=/path/to/cli" >&2; \
		exit 127; \
	fi
	@$(DOCKER) --version

image: check-container-cli
	$(DOCKER) build \
		$(if $(DOCKER_PLATFORM),--platform "$(DOCKER_PLATFORM)",) \
		--build-arg BUILD_IMAGE="$(BUILD_IMAGE)" \
		--build-arg RUNTIME_IMAGE="$(RUNTIME_IMAGE)" \
		-t "$(IMAGE)" \
		"$(ROOT_DIR)"

docker:
	snz1dpctl make docker

package:
	snz1dpctl make package

publish:
	snz1dpctl make publish

clean:
	"$(MVNW)" clean

# ============================================================
# 文档站点（VitePress，自包含构建单元，位于 docs/ 目录）
# 独立组件描述见 docs/BUILD.yaml，可用 snz1dpctl 在 docs/ 下直接发布
# ============================================================

DOCS_DIR := $(ROOT_DIR)/docs
DOCS_IMAGE ?= snz1.cn/taiyiflow/javasdk-docs:latest
DOCS_CONTAINER ?= javasdk-docs
DOCS_PORT ?= 5002

# 安装文档依赖
docs-init:
	cd "$(DOCS_DIR)" && npm install

# 文档开发模式（本地预览，默认 http://localhost:5173/docs/）
docs-dev:
	cd "$(DOCS_DIR)" && npm run docs:dev

# 文档构建（Docker 镜像用，base=/taiyi/javasdk/docs/）
docs-build:
	cd "$(DOCS_DIR)" && VITEPRESS_BASE_PATH=/taiyi/javasdk/docs/ npm run docs:build

# 文档构建（GitHub Pages 用，base=/taiyiflow-javasdk/）
docs-build-gh:
	cd "$(DOCS_DIR)" && npm run docs:build:gh

# 文档构建预览
docs-preview:
	cd "$(DOCS_DIR)" && npm run docs:preview

# 构建文档站点镜像（构建上下文为 docs/ 目录）
docs-image: check-container-cli
	$(DOCKER) build \
		$(if $(DOCKER_PLATFORM),--platform "$(DOCKER_PLATFORM)",) \
		-f "$(DOCS_DIR)/Dockerfile.docs" \
		-t "$(DOCS_IMAGE)" \
		"$(DOCS_DIR)"

# 运行文档站点容器（可通过 WEB_CONTEXT_PATH 重定位访问路径）
docs-run:
	$(DOCKER) run -d --name $(DOCS_CONTAINER) -p $(DOCS_PORT):80 $(DOCS_IMAGE)

# 停止并删除文档站点容器
docs-stop:
	- $(DOCKER) stop $(DOCS_CONTAINER)
	- $(DOCKER) rm $(DOCS_CONTAINER)
