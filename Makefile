# payments-api/Makefile
# Conveniência sobre Maven e Docker Compose. Uso: make <target>

.PHONY: help build test it bench run clean fmt up up-core down nuke ps logs-infra token

MVNW := ./mvnw
ifeq ($(OS),Windows_NT)
  # Se rodando em cmd/powershell sem bash, use mvnw.cmd
  SHELL := bash.exe
endif

help: ## Exibe esta ajuda
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

build: ## Compila o projeto
	$(MVNW) compile

test: ## Executa os testes unitários
	$(MVNW) test

it: ## Executa a suíte de integração
	$(MVNW) verify

bench: ## Executa o benchmark e gerador de 10.000 pagamentos mocados
	bash scripts/benchmark.sh --total 10000 --concurrency 50

run: ## Executa a API com profile local (porta 8181)
	SPRING_PROFILES_ACTIVE=local $(MVNW) spring-boot:run

clean: ## Limpa os artefatos de build (target/)
	$(MVNW) clean

fmt: ## Aplica formatação de código
	$(MVNW) spotless:apply

up: ## Sobe toda a infraestrutura local (Postgres, Redis, Kafka, Kafka UI, Keycloak, LGTM)
	docker compose --profile tools --profile auth --profile obs up -d

up-core: ## Sobe apenas a infraestrutura essencial (Postgres, Redis, Kafka)
	docker compose up -d postgres redis kafka

down: ## Para todos os serviços do docker compose
	docker compose --profile tools --profile auth --profile obs down

nuke: ## Destrói containers e volumes persistentes
	docker compose --profile tools --profile auth --profile obs down -v

ps: ## Lista status dos containers do projeto
	docker compose ps

logs-infra: ## Visualiza logs da infraestrutura
	docker compose logs -f

token: ## Obtém um token JWT de teste para o merchant-acme via Keycloak
	@curl -s -X POST http://localhost:8080/realms/payments/protocol/openid-connect/token \
		-d "grant_type=client_credentials" \
		-d "client_id=merchant-acme" \
		-d "client_secret=acme-secret" \
		-d "scope=payments:read payments:write" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4