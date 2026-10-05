# Relatório Executivo de Benchmark & Carga da API de Pagamentos (100k)

> **Data da Execução:** 2026-10-05T21:27:24.624924746Z
> **Ambiente:** Local / WSL 2 (Debian 13) • Java 21 LTS (Virtual Threads)
> **Alvo Testado:** `http://localhost:8181`

---

## Resumo Executivo

| Fase do Benchmark | Total Requisições | Throughput (RPS) | Latência p50 | Latência p95 | Latência p99 | Status Predominante |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Ingestão Assíncrona (POST /v1/payments)** | 10,000 | **689.2 req/s** | 59.52 ms | 154.56 ms | 227.01 ms | HTTP 202 Accepted (10000) |
| **2. Replay Idempotente (Redis)** | 2,000 | **1149.9 req/s** | 36.57 ms | 97.12 ms | 128.35 ms | HTTP 200 OK (Replay) (2000) |
| **3. Transições (POST /authorize)** | 5,000 | **622.9 req/s** | 70.65 ms | 165.45 ms | 225.27 ms | HTTP 200 OK (5000) |

---

## Análise de Performance & Resiliência Distribuída

### 1. Ingestão Assíncrona vs Replay em Cache
* **Latência Média de Ingestão (Redis CAS + Kafka Ingress):** `72.21 ms`
* **Latência Média de Replay (Redis Lua CAS Hit):** `42.98 ms`
* **Fator de Aceleração do Cache de Idempotência:** **1.7x mais rápido** que o ciclo de ingestão.

### 2. Distribuição Completa de Percentis (Fase de Criação)

| Métrica | Valor |
| :--- | :--- |
| **Tempo Total de Execução** | `14.51 segundos (0.2 minutos)` |
| **Throughput Médio** | `689.2 requisições/segundo` |
| **Latência Mínima** | `4.58 ms` |
| **Latência p50 (Mediana)** | `59.52 ms` |
| **Latência p90** | `126.29 ms` |
| **Latência p95** | `154.56 ms` |
| **Latência p99** | `227.01 ms` |
| **Latência Máxima** | `894.03 ms` |

---

## Como Validar os Dados Gerados nos Dashboards

1. **Grafana LGTM** (`http://localhost:3000`):
   * Abra o dashboard **Payments API - Overview**.
   * Observe o card **Total Payments Created** exibindo o volume completo populado (100k+).
   * Observe os gráficos de **Throughput**, **Latency (p95/p99)** e **Payments Created by Currency**.

2. **Apache Kafka UI** (`http://localhost:8085`):
   * Acesse o tópico `payments.events`.
   * Inspecione as mensagens CloudEvents v1.0 publicadas com suas chaves de partição e payloads.

3. **PostgreSQL 16**:
   ```sql
   SELECT status, count(*) FROM payments GROUP BY status;
   SELECT status, count(*) FROM outbox_events GROUP BY status;
   ```
