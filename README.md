# payments-api

> API de processamento de pagamentos com idempotência, saga pattern, circuit breaker e observability nativa.

## Por que este projeto

Construído para demonstrar engenharia de backend em nível senior/staff: domínio modelado em
DDD, persistência isolada por porta, idempotency-key, otimistic locking, resiliência com
Resilience4j, tracing distribuído via OpenTelemetry.

Foco: como *eu* organizo um serviço Java/Spring de pagamento real — não um CRUD com spring-data-rest.

## Stack

| camada | tecnologia |
|---|---|
| linguagem | Java 21 (records, sealed types, pattern matching) |
| framework | Spring Boot 3.3 |
| build | Gradle 8.10 (Kotlin DSL) |
| persistência | PostgreSQL 16 + Flyway + Spring Data JPA |
| cache | Redis 8 |
| mensageria | Kafka 3.7 (a adicionar — saga) |
| resiliência | Resilience4j (circuit breaker, retry, bulkhead, rate limiter) |
| observabilidade | OpenTelemetry → OTLP + Micrometer → Prometheus |
| teste | JUnit 5 + Testcontainers + k6 (load) + Toxiproxy (chaos) |

## Arquitetura

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  PaymentCtrl │───▶│ CreatePayment│───▶│   Payment    │  (domain)
└──────────────┘    └──────────────┘    │  Repository  │
       ▲                                  └──────┬───────┘
       │                                         │
       │              ┌──────────────────────────▼───────────┐
       └──────────────│ JpaPaymentRepositoryAdapter (infra)  │
                      └──────────────────────────────────────┘
```

Hexagonal / Clean: `domain` não importa nada de spring/data. `infrastructure` implementa
as portas. `interfaces` traduz HTTP ↔ use case.

## Quick start

Pré-requisitos: Java 21, PostgreSQL 16, Redis 8 rodando local.

```bash
# 1. subir deps
docker compose up -d            # ou instalar nativo — ver docs/local-setup.md

# 2. rodar migrações
./gradlew flywayMigrate

# 3. subir API
./gradlew bootRun

# 4. chamada
curl -X POST http://localhost:8181/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"payerId":"...","payeeId":"...","amount":"1500.00","currency":"BRL"}'
```

## Destaque técnico

- **Idempotência**: header `Idempotency-Key` único, constraint SQL + replay no use case.
- **State machine estrita**: `PaymentStatus.canTransitionTo` impede transições inválidas.
- **Optimistic locking**: `@Version` na entity previne double-spend em concorrência.
- **Hexagonal**: domínio puro, JPA isolado em `infrastructure`.
- **Resilience4j**: circuit breaker configurado em `application.yml`, fallback em chamadas externas.
- **OpenTelemetry**: traces propagados para chamadas externas e listeners assíncronos.

## Estrutura

```
src/main/java/com/portfolio/payments/
├── domain/         # agregado, value objects, porta de repository — sem dependência spring
├── application/    # use cases (CreatePaymentUseCase, ...)
├── infrastructure/ # adaptadores JPA, clients externos, config
└── interfaces/     # REST controllers, request/response DTOs, validação

src/main/resources/
├── application.yml
└── db/migration/   # Flyway: V1__init_payments.sql, ...

src/test/java/com/portfolio/payments/
└── PaymentDomainTest.java
```

## Roadmap

- [ ] saga pattern (PaymentAuthorized → CaptureExternalService → Settle)
- [ ] Kafka producer para `payment.events`
- [ ] Webhook de confirmação
- [ ] Idempotency key store em Redis com TTL
- [ ] k6 load test + Grafana dashboard
- [ ] Testcontainers + Toxiproxy integration tests
- [ ] Postmortem: simular incidente "double-charge em retry"

## Próximo projeto

[`event-sourcing-core`](https://github.com/<user>/event-sourcing-core) — implementação
pura de event sourcing em Java sem framework, com read model poliglota.