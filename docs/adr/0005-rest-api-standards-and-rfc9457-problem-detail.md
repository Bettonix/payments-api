# ADR 0005: Padrões RESTful v1, Transições de Estado Stripe-Style e RFC 9457 ProblemDetail

- **Data**: 2026-10-05
- **Status**: Aceito

## Contexto

APIs financeiras necessitam de clareza máxima em seus contratos públicos. Problemas clássicos observados em versões legadas:
1. Mistura de verbos HTTP para transições de estado (ex.: `PUT /payments/{id}` que sobrescrevia estado arbitrariamente sem validação de máquina de estados).
2. Respostas de erro sem formato uniforme (respostas em texto plano, JSON customizado inconsistente ou HTML de erro do Tomcat/Spring).
3. Atualizações concorrentes perdidas (*Lost Updates*) em ambientes distribuídos onde dois processos tentam alterar o estado do pagamento simultaneamente.

## Decisão

1. **Versionamento e Endpoints Especializados (Stripe-Style)**:
   - Prefixo oficial de rotas: `/v1/payments`.
   - Transições de máquina de estados implementadas como sub-recursos acionados por POST:
     - `POST /v1/payments`: Criação com idempotência.
     - `POST /v1/payments/{id}/authorize`: Transição de `PENDING` para `AUTHORIZED`.
     - `POST /v1/payments/{id}/capture`: Transição de `AUTHORIZED` para `CAPTURED`.
     - `POST /v1/payments/{id}/settle`: Transição de `CAPTURED` para `SETTLED`.
     - `POST /v1/payments/{id}/fail`: Transição para `FAILED` com motivo formal.
     - `POST /v1/payments/{id}/cancel`: Cancelamento de pagamentos pendentes/autorizados.

2. **RFC 9457 Problem Details for HTTP APIs**:
   - Todo erro 4xx ou 5xx retorna o cabeçalho `Content-Type: application/problem+json` e a estrutura padrão:
     - `type`: URI identificando o tipo de erro (ex.: `urn:problem-type:idempotency-conflict`).
     - `title`: Descrição curta e legível por humanos do problema.
     - `status`: Código de status HTTP.
     - `detail`: Explicação detalhada da ocorrência específica.
     - `instance`: URI da requisição que gerou o erro.
     - Extensões customizadas (ex.: `invalidParams`, `retryAfter`, `currentStatus`).

3. **Controle de Concorrência Otimista com ETag e If-Match (HTTP 412)**:
   - Toda resposta de leitura (`GET /v1/payments/{id}`) ou criação retorna o cabeçalho `ETag: "W/\"<version>\""` baseado no campo de versão da entidade.
   - Requisições de transição de estado aceitam opcionalmente o cabeçalho `If-Match`. Se a versão informada pelo cliente for diferente da versão atual no banco, a API aborta com **HTTP 412 Precondition Failed**, evitando transições baseadas em estado desatualizado.

## Consequências

- **(+) Ergonomia para SDKs e Clientes**: Facilidade de consumo e tratamento de exceções previsível através do RFC 9457.
- **(+) Prevenção de Condições de Corrida**: O uso combinado de ETag/If-Match e optimistic locking impede *lost updates*.
- **(+) Rastreabilidade de Negócio**: Transições explícitas facilitam a emissão de métricas e auditoria detalhada de cada ciclo de vida financeiro.
