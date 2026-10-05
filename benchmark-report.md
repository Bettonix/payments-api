# 📊 Relatório Executivo de Benchmark & Carga da API de Pagamentos

> **Data da Execução:** 2026-10-05T17:31:47.711605755Z
> **Ambiente:** Local / WSL 2 (Debian 13) • Java 21 LTS (Virtual Threads)
> **Alvo Testado:** `http://localhost:8181`

---

## 🚀 Resumo Executivo

| Fase do Benchmark | Total Requisições | Throughput (RPS) | Latência p50 | Latência p95 | Latência p99 | Status Predominante |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Criação (POST /payments)** | 10,000 | **529.1 req/s** | 88.23 ms | 164.51 ms | 228.85 ms | HTTP 201 Created (10000) |
| **2. Replay Idempotente (Redis)** | 500 | **1398.7 req/s** | 31.09 ms | 67.79 ms | 93.04 ms | HTTP 201 Replay (500) |
| **3. Transições (POST /authorize)** | 1,000 | **780.9 req/s** | 56.65 ms | 111.67 ms | 140.89 ms | HTTP 200 OK (1000) |

---

## 🔬 Análise de Performance & Resiliência Distribuída

### 1. Criação Transacional vs Replay em Cache
* **Latência Média de Criação (PostgreSQL + Outbox + Redis CAS):** `94.05 ms`
* **Latência Média de Replay (Redis Lua CAS Hit):** `34.64 ms`
* **Fator de Aceleração do Cache de Idempotência:** **2.7x mais rápido** que o ciclo transacional completo.

### 2. Distribuição Completa de Percentis (Fase de Criação)

| Métrica | Valor |
| :--- | :--- |
| **Tempo Total de Execução** | `18.90 segundos` |
| **Throughput Médio** | `529.1 requisições/segundo` |
| **Latência Mínima** | `20.10 ms` |
| **Latência p50 (Mediana)** | `88.23 ms` |
| **Latência p90** | `142.28 ms` |
| **Latência p95** | `164.51 ms` |
| **Latência p99** | `228.85 ms` |
| **Latência Máxima** | `437.27 ms` |

---

## 📡 Como Validar os Dados Gerados nos Dashboards

1. **Grafana LGTM** (`http://localhost:3000`):
   * Abra o dashboard **Payments API - Overview**.
   * Observe o card **Total Payments Created** exibindo o volume completo populado.
   * Observe os gráficos de **Throughput**, **Latency (p95/p99)** e **Payments Created by Currency**.

2. **Apache Kafka UI** (`http://localhost:8085`):
   * Acesse o tópico `payments.events`.
   * Inspecione as mensagens CloudEvents v1.0 publicadas com suas chaves de partição e payloads.

3. **PostgreSQL 16**:
   ```sql
   SELECT status, count(*) FROM payments GROUP BY status;
   SELECT status, count(*) FROM outbox_events GROUP BY status;
   ```
