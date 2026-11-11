.PHONY: test build worker-package check-container-cli image docker package publish clean

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
