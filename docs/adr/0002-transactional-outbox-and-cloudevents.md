# ADR 0002: Transactional Outbox Pattern e Especificação CloudEvents v1.0

- **Data**: 2026-10-05
- **Status**: Aceito
- **Contexto**: ADR 0001 (Arquitetura Hexagonal)

## Contexto

Em sistemas financeiros distribuídos, pagamentos geram eventos de domínio cruciais (ex.: `PaymentCreated`, `PaymentAuthorized`, `PaymentSettled`). A publicação direta de eventos no Apache Kafka logo após a gravação no banco incorre no clássico problema da **gravação dupla (dual-write anomaly)**: se o banco commitar mas o broker falhar, o evento é perdido; se o evento for publicado mas o commit falhar, consumidores processam eventos fantasmas. O uso de transações distribuídas (2PC / XA) introduz latência severa, acoplamento e pontos únicos de falha.

Além disso, a integração com múltiplos consumidores e parceiros corporativos exige um contrato de metadados padronizado para rastreabilidade e roteamento sem dependência de schemas proprietários.

## Decisão

1. **Transactional Outbox Atômico**:
   - Os eventos de domínio são persistidos na tabela `outbox_events` na **mesma transação relacional** que altera o agregado `Payment`.
   - Se a transação do pagamento commitar, o evento está garantido em disco no PostgreSQL; se sofrer rollback, o evento é descartado atomicamente.

2. **Drenagem Assíncrona e Segregação de I/O (`OutboxRelay`)**:
   - O poller `OutboxRelay` opera estritamente fora de transações de longa duração (`@Transactional` removido do loop principal).
   - Utiliza lock pessimista por lote com concorrência segura (`SELECT ... FOR UPDATE SKIP LOCKED`) e concessão de lease temporal (`locked_until`), permitindo múltiplas instâncias concorrentes da API sem colisão.
   - Cada chamada de rede externa (Kafka I/O) ocorre de forma isolada, atualizando o status do outbox (`PUBLISHED`, `FAILED` com backoff exponencial ou dead-lettering após 5 tentativas).
   - O publisher é protegido por Circuit Breaker do Resilience4j: se o Kafka estiver degradado, o relay preserva as tentativas dos registros e entra em estado de espera.

3. **Padronização CloudEvents v1.0 (Binary Mode)**:
   - Os eventos são publicados no tópico `payments.events` no modo binário do CloudEvents:
     - `ce_specversion`: `1.0`
     - `ce_id`: UUID do evento outbox (usado para desduplicação no consumidor)
     - `ce_source`: `/merchants/{merchant_id}/payments`
     - `ce_type`: `com.portfolio.payments.{event_type}.v1`
     - `ce_subject`: `payment_id` (usado também como chave da partição Kafka para garantir ordenação estrita por pagamento)
     - `ce_time`: Timestamp UTC ISO-8601
     - `ce_merchantid`: ID do tenant/merchant
     - `content-type`: `application/json`

## Consequências

- **(+) Garantia At-Least-Once**: Nenhum evento é perdido mesmo em caso de crash do broker ou da aplicação.
- **(+) Ordenação por Agregado**: A chave do Kafka baseada no `payment_id` garante que autorizações, capturas e liquidações do mesmo pagamento sejam processadas estritamente em ordem na mesma partição.
- **(+) Interoperabilidade**: Qualquer consumidor compatível com CloudEvents v1.0 pode inspecionar cabeçalhos de roteamento sem desserializar o payload JSON.
- **(-) Idempotência Obrigatória no Consumidor**: Como a garantia é *at-least-once*, consumidores devem utilizar o cabeçalho `ce_id` para desduplicação.
