# 📊 Relatório Executivo de Benchmark & Carga da API de Pagamentos (100k)

> **Data da Execução:** 2026-10-05T18:45:41.524843815Z
> **Ambiente:** Local / WSL 2 (Debian 13) • Java 21 LTS (Virtual Threads)
> **Alvo Testado:** `http://localhost:8181`

---

## 🚀 Resumo Executivo

| Fase do Benchmark | Total Requisições | Throughput (RPS) | Latência p50 | Latência p95 | Latência p99 | Status Predominante |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Ingestão Assíncrona (POST /v1/payments)** | 10,000 | **703.2 req/s** | 59.28 ms | 147.03 ms | 226.22 ms | HTTP 202 Accepted (10000) |
| **2. Replay Idempotente (Redis)** | 2,000 | **1108.4 req/s** | 40.77 ms | 91.24 ms | 115.28 ms | HTTP 200 OK (Replay) (2000) |
| **3. Transições (POST /authorize)** | 5,000 | **605.2 req/s** | 73.55 ms | 164.87 ms | 222.77 ms | HTTP 200 OK (5000) |

---

## 🔬 Análise de Performance & Resiliência Distribuída

### 1. Ingestão Assíncrona vs Replay em Cache
* **Latência Média de Ingestão (Redis CAS + Kafka Ingress):** `70.77 ms`
* **Latência Média de Replay (Redis Lua CAS Hit):** `44.36 ms`
* **Fator de Aceleração do Cache de Idempotência:** **1.6x mais rápido** que o ciclo de ingestão.

### 2. Distribuição Completa de Percentis (Fase de Criação)

| Métrica | Valor |
| :--- | :--- |
| **Tempo Total de Execução** | `14.22 segundos (0.2 minutos)` |
| **Throughput Médio** | `703.2 requisições/segundo` |
| **Latência Mínima** | `6.52 ms` |
| **Latência p50 (Mediana)** | `59.28 ms` |
| **Latência p90** | `120.86 ms` |
| **Latência p95** | `147.03 ms` |
| **Latência p99** | `226.22 ms` |
| **Latência Máxima** | `1045.14 ms` |

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
