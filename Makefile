# payments-api/Makefile
# Conveniência sobre gradle. Uso: make <target>

.PHONY: help build test run clean fmt lint compose-up compose-down logs

POSTGRES_HOST ?=127.0.0.1
POSTGRES_PORT ?=5432
POSTGRES_DB   ?=payments_api
REDIS_HOST    ?=127.0.0.1
REDIS_PORT    ?=6379

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

build: ## compila tudo
	./gradlew build -x test

test: ## roda testes unit + integration
	./gradlew test

run: ## sobe a API na porta 8080
	POSTGRES_HOST=$(POSTGRES_HOST) POSTGRES_PORT=$(POSTGRES_PORT) \
	REDIS_HOST=$(REDIS_HOST) REDIS_PORT=$(REDIS_PORT) \
	./gradlew bootRun

clean: ## limpa build/
	./gradlew clean

fmt:
	./gradlew spotlessApply

lint:
	./gradlew check

compose-up: ## sobe postgres + redis local
	@echo "use docker compose se disponível; este projeto não inclui compose por padrão"

logs:
	@ls -la build/reports/tests/test/ 2>/dev/null || echo "rode 'make test' primeiro"