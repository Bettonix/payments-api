#!/usr/bin/env bash
# ==============================================================================
# Payments API - Benchmark Runner & Mock Data Generator
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

HOST="${HOST:-http://localhost:8181}"
KEYCLOAK="${KEYCLOAK:-http://localhost:8080}"

echo "Validando prontidão dos serviços..."

# Verifica se a API está online
if ! curl -sf "$HOST/actuator/health" > /dev/null 2>&1; then
  echo "❌ Erro: Payments API não está respondendo em $HOST."
  echo "Certifique-se de iniciar a aplicação antes de rodar o benchmark: ./mvnw spring-boot:run"
  exit 1
fi

# Verifica se o Keycloak está online
if ! curl -sf "$KEYCLOAK/realms/payments" > /dev/null 2>&1; then
  echo "❌ Erro: Keycloak não está respondendo em $KEYCLOAK."
  echo "Inicie a infraestrutura: docker compose --profile auth --profile obs up -d"
  exit 1
fi

echo "✅ Serviços operacionais. Iniciando Benchmark Java 21 Virtual Threads..."
echo ""

# Executa o Benchmark Java
cd "$PROJECT_ROOT"
java scripts/Benchmark.java "$@"
