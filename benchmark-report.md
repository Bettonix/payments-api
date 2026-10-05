# 📊 Relatório Executivo de Benchmark & Carga da API de Pagamentos (100k)

> **Data da Execução:** 2026-10-05T19:03:20.235957622Z
> **Ambiente:** Local / WSL 2 (Debian 13) • Java 21 LTS (Virtual Threads)
> **Alvo Testado:** `http://localhost:8181`

---

## 🚀 Resumo Executivo

| Fase do Benchmark | Total Requisições | Throughput (RPS) | Latência p50 | Latência p95 | Latência p99 | Status Predominante |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Ingestão Assíncrona (POST /v1/payments)** | 2,000 | **883.1 req/s** | 21.70 ms | 68.43 ms | 108.40 ms | HTTP 202 Accepted (2000) |
| **2. Replay Idempotente (Redis)** | 2,000 | **1715.4 req/s** | 11.74 ms | 25.38 ms | 91.19 ms | HTTP 200 OK (Replay) (2000) |
| **3. Transições (POST /authorize)** | 2,000 | **1039.7 req/s** | 22.35 ms | 38.59 ms | 48.61 ms | HTTP 200 OK (2000) |

---

## 🔬 Análise de Performance & Resiliência Distribuída

### 1. Ingestão Assíncrona vs Replay em Cache
* **Latência Média de Ingestão (Redis CAS + Kafka Ingress):** `27.86 ms`
* **Latência Média de Replay (Redis Lua CAS Hit):** `14.43 ms`
* **Fator de Aceleração do Cache de Idempotência:** **1.9x mais rápido** que o ciclo de ingestão.

### 2. Distribuição Completa de Percentis (Fase de Criação)

| Métrica | Valor |
| :--- | :--- |
| **Tempo Total de Execução** | `2.26 segundos (0.0 minutos)` |
| **Throughput Médio** | `883.1 requisições/segundo` |
| **Latência Mínima** | `4.91 ms` |
| **Latência p50 (Mediana)** | `21.70 ms` |
| **Latência p90** | `52.29 ms` |
| **Latência p95** | `68.43 ms` |
| **Latência p99** | `108.40 ms` |
| **Latência Máxima** | `202.22 ms` |

---

## 📡 Como Validar os Dados Gerados nos Dashboards

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
