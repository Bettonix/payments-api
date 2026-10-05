# 📊 Relatório Executivo de Benchmark & Carga da API de Pagamentos (100k)

> **Data da Execução:** 2026-10-05T17:38:41.103380228Z
> **Ambiente:** Local / WSL 2 (Debian 13) • Java 21 LTS (Virtual Threads)
> **Alvo Testado:** `http://localhost:8181`

---

## 🚀 Resumo Executivo

| Fase do Benchmark | Total Requisições | Throughput (RPS) | Latência p50 | Latência p95 | Latência p99 | Status Predominante |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Criação (POST /payments)** | 100,000 | **1228.1 req/s** | 46.24 ms | 79.30 ms | 112.19 ms | HTTP 201 Created (100000) |
| **2. Replay Idempotente (Redis)** | 2,000 | **3361.2 req/s** | 15.65 ms | 34.52 ms | 45.76 ms | HTTP 201 Replay (2000) |
| **3. Transições (POST /authorize)** | 5,000 | **1260.6 req/s** | 42.63 ms | 92.98 ms | 121.87 ms | HTTP 200 OK (5000) |

---

## 🔬 Análise de Performance & Resiliência Distribuída

### 1. Criação Transacional vs Replay em Cache
* **Latência Média de Criação (PostgreSQL + Outbox + Redis CAS):** `48.72 ms`
* **Latência Média de Replay (Redis Lua CAS Hit):** `17.48 ms`
* **Fator de Aceleração do Cache de Idempotência:** **2.8x mais rápido** que o ciclo transacional completo.

### 2. Distribuição Completa de Percentis (Fase de Criação)

| Métrica | Valor |
| :--- | :--- |
| **Tempo Total de Execução** | `81.42 segundos (1.4 minutos)` |
| **Throughput Médio** | `1228.1 requisições/segundo` |
| **Latência Mínima** | `7.82 ms` |
| **Latência p50 (Mediana)** | `46.24 ms` |
| **Latência p90** | `67.44 ms` |
| **Latência p95** | `79.30 ms` |
| **Latência p99** | `112.19 ms` |
| **Latência Máxima** | `290.03 ms` |

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
