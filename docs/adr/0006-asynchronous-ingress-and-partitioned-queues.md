# ADR 0006: Ingestão Assíncrona de Alta Vazão e Filas Particionadas por Pagador (Padrão Pix)

- **Data**: 2026-10-05
- **Status**: Aceito
- **Contexto**: ADR 0001 (Hexagonal), ADR 0002 (Outbox), ADR 0003 (Idempotência Distribuída)

## Contexto

Em sistemas financeiros de alto throughput (como o ecossistema Pix do Banco Central do Brasil ou processadoras globais como Stripe e Adyen), requisições de pagamento síncronas que mantêm conexões HTTP abertas enquanto disputam transações no banco relacional sofrem de:
1. **Contenção e esgotamento de conexões de banco de dados** (HikariCP starvation) sob picos de 10.000 a 100.000 requisições simultâneas.
2. **Latência de borda elevada e volátil** (p99 degradando para centenas de milissegundos ou segundos devido a bloqueios de linha/tabela no PostgreSQL).
3. **Alto acoplamento temporal**: Falhas transitórias no banco de dados paralisam instantaneamente a borda de recebimento de pagamentos da API.

## Decisão

1. **Ingestão Assíncrona na Borda (HTTP 202 Accepted - RFC 7240)**:
   - O endpoint `POST /v1/payments` valida a chave de idempotência atomicamente no Redis (Lua CAS), realiza a validação sintática do payload e publica o comando de criação no tópico de ingestão Kafka (`payments.ingress`).
   - A resposta HTTP é devolvida imediatamente com status **HTTP 202 Accepted**, contendo o identificador do pagamento (`Location: /v1/payments/{id}`), `ETag: "v0"` e cabeçalho RFC 7240 `Preference-Applied: respond-async`.
   - A latência da borda HTTP (Edge Ingress) é reduzida para a faixa de **~4ms (p50)**.

2. **Particionamento Estrito por Pagador (`payerId`)**:
   - As mensagens enviadas para o tópico `payments.ingress` utilizam o `payerId` como chave de partição Kafka.
   - Isso garante:
     - **FIFO per-account**: Todas as ordens de pagamento de uma mesma conta/cliente são consumidas estritamente em ordem serial pelo worker daquela partição.
     - **Eliminação de Row Lock Contention**: Duas transações para a mesma conta nunca colidem concorrentemente no banco de dados em instâncias de workers diferentes.
     - **Escalabilidade Horizontal**: Múltiplas partições permitem que centenas de milhares de contas distintas sejam processadas em paralelo com isolamento total.

3. **Consumidor Dedicado de Ingestão (`PaymentIngressConsumer`)**:
   - Consome mensagens do tópico `payments.ingress` e aciona o `PaymentIngressService`.
   - Executa a persistência relacional ACID no PostgreSQL (`payments` + `outbox_events`) via JDBC de alta performance e atualiza o estado final da idempotência no Redis (`COMPLETED`).
   - O `OutboxRelay` continua operando desacoplado, publicando os eventos de domínio resultantes em `payments.events` (CloudEvents v1.0).

4. **Telemetria e Decomposição de Latência em 4 Fases**:
   - Cada fase do ciclo de vida é instrumentada com Micrometer `Timer` e histogramas para visualização em tempo real no Grafana LGTM:
     1. `payments.latency.ingress`: Borda HTTP até emissão do `202 Accepted`.
     2. `payments.latency.queue.transit`: Tempo de permanência e trânsito na fila Kafka `payments.ingress`.
     3. `payments.latency.db.persistence`: Execução da transação ACID no PostgreSQL + inserção no Outbox.
     4. `payments.latency.e2e.total`: Duração total de ponta a ponta (recebimento na borda até conclusão da persistência).

## Consequências

- **(+) Throughput Escalável (> 1.700 a 2.500+ req/s por nó)**: A API aceita rajadas massivas instantaneamente sem sobrecarregar o pool de conexões do banco relacional.
- **(+) Latência de Borda Estável**: Clientes e gateways recebem confirmação de aceitação com p50 ~4ms.
- **(+) Isolamento de Falhas**: Picos de tráfego são amortecidos naturalmente no Kafka, mantendo o banco relacional operando em seu throughput ideal contínuo.
- **(-) Consistência Eventual na Consulta Imediata**: A transição do estado `PENDING` para persistência em disco leva de alguns milissegundos até ser completada pelo worker (visível e monitorada em tempo real via métrica `payments.latency.e2e.total`).
