.DEFAULT_GOAL := build
.PHONY: build test lint

GRADLE := ./gradlew --console=plain

build:
	$(GRADLE) build

test:
	$(GRADLE) test $(if $(TESTS),--tests "$(TESTS)")

lint:
	$(GRADLE) spotlessApply
