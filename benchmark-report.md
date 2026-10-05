# 📊 Relatório Executivo de Benchmark & Carga da API de Pagamentos (100k)

> **Data da Execução:** 2026-10-05T18:14:46.477034210Z
> **Ambiente:** Local / WSL 2 (Debian 13) • Java 21 LTS (Virtual Threads)
> **Alvo Testado:** `http://localhost:8181`

---

## 🚀 Resumo Executivo

| Fase do Benchmark | Total Requisições | Throughput (RPS) | Latência p50 | Latência p95 | Latência p99 | Status Predominante |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Ingestão Assíncrona (POST /v1/payments)** | 20,000 | **1392.3 req/s** | 35.48 ms | 102.59 ms | 146.98 ms | HTTP 202 Accepted (20000) |
| **2. Replay Idempotente (Redis)** | 2,000 | **1649.6 req/s** | 30.77 ms | 82.35 ms | 113.44 ms | HTTP 200 OK (Replay) (2000) |
| **3. Transições (POST /authorize)** | 5,000 | **881.0 req/s** | 58.21 ms | 142.60 ms | 209.34 ms | HTTP 200 OK (5000) |

---

## 🔬 Análise de Performance & Resiliência Distribuída

### 1. Ingestão Assíncrona vs Replay em Cache
* **Latência Média de Ingestão (Redis CAS + Kafka Ingress):** `42.88 ms`
* **Latência Média de Replay (Redis Lua CAS Hit):** `35.81 ms`
* **Fator de Aceleração do Cache de Idempotência:** **1.2x mais rápido** que o ciclo de ingestão.

### 2. Distribuição Completa de Percentis (Fase de Criação)

| Métrica | Valor |
| :--- | :--- |
| **Tempo Total de Execução** | `14.36 segundos (0.2 minutos)` |
| **Throughput Médio** | `1392.3 requisições/segundo` |
| **Latência Mínima** | `3.19 ms` |
| **Latência p50 (Mediana)** | `35.48 ms` |
| **Latência p90** | `84.09 ms` |
| **Latência p95** | `102.59 ms` |
| **Latência p99** | `146.98 ms` |
| **Latência Máxima** | `363.40 ms` |

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
