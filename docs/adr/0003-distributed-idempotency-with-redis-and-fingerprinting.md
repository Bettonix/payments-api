# ADR 0003: Idempotência Distribuída em Duas Camadas com Redis, Fingerprinting e Fail-Open

- **Data**: 2026-10-05
- **Status**: Aceito
- **Contexto**: ADR 0001 (Arquitetura Hexagonal), C1 e C8 Fixes

## Contexto

APIs financeiras enfrentam frequentes retentativas de rede de clientes, timeouts intermediários e rajadas de tráfego concorrente com a mesma chave de idempotência (`Idempotency-Key`).
Existem três riscos críticos:
1. **Cobrança Dupla (Double Charging)**: Executar duas criações de pagamento para a mesma intenção de compra.
2. **Reuso Malicioso ou Acidental de Chaves com Payloads Divergentes**: Um cliente reutilizar uma chave anterior enviando valores, moedas ou pagadores diferentes.
3. **Ponto Único de Falha no Cache**: Se a camada de cache (Redis) falhar, a API de pagamentos não pode interromper completamente a operação se o banco relacional estiver saudável.

## Decisão

Implementar uma estratégia de idempotência em duas camadas (Redis Distribuído + PostgreSQL ACID):

1. **Fingerprinting Canônico SHA-256 (C8 Fix)**:
   - Toda requisição com `Idempotency-Key` calcula um hash SHA-256 sobre a tupla canônica `(merchantId, payerId, payeeId, amount, currency)`.
   - Se uma chave for reenviada com um fingerprint diferente, a API rejeita imediatamente com HTTP 422 Unprocessable Content (`IdempotencyPayloadMismatchException`).

2. **Lock Distribuído e Cache no Redis via Script Lua Atômico**:
   - Chave no Redis: `idemp:{merchant_id}:{idempotency_key}`.
   - Operação atômica em Lua (`tryAcquire`):
     - Chave inexistente: Grava `status=PROCESSING`, `fingerprint` e define TTL curto in-flight (120 segundos). Retorna `ACQUIRED`.
     - Chave existente com `status=PROCESSING`: Se o fingerprint coincidir, identifica concorrência simultânea e retorna `IN_PROGRESS` (HTTP 409 Conflict com cabeçalho `Retry-After: 1`).
     - Chave existente com `status=COMPLETED`: Retorna o `paymentId` em cache. A API reidrata e responde com HTTP 201 Created e cabeçalho `Idempotent-Replayed: true`.
     - Fingerprint divergente: Retorna `MISMATCH` (HTTP 422).

3. **Resiliência e Fail-Open (Resilience4j Circuit Breaker)**:
   - As chamadas ao Redis são encapsuladas por Circuit Breaker.
   - Em caso de timeout, falha de rede ou circuito aberto no Redis, a API adota **fail-open elegante**: a requisição segue diretamente para o PostgreSQL.
   - No PostgreSQL, a restrição de unicidade `UNIQUE (merchant_id, idempotency_key)` e o método `repository.insert()` com flush imediato atuam como o solo firme de verdade absoluta contra corridas concorrentes (C1 Fix).

## Consequências

- **(+) Baixíssima Latência**: Replays e verificações de concorrência são atendidos em sub-milissegundos pelo Redis sem sobrecarregar o banco de dados.
- **(+) Alta Disponibilidade**: Falhas no cluster Redis não derrubam a API de pagamentos.
- **(+) Integridade Estrita**: O fingerprinting impede o reuso de chaves para fraudes ou confusão de transações.
