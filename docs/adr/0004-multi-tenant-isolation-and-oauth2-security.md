# ADR 0004: Isolamento Multi-Tenant e Autenticação OAuth2 Resource Server

- **Data**: 2026-10-05
- **Status**: Aceito
- **Contexto**: OWASP API Security Top 10 (API1: BOLA / Broken Object Level Authorization)

## Contexto

A API de pagamentos precisa operar como uma plataforma corporativa multi-tenant, atendendo múltiplos estabelecimentos ou empresas (`merchants`) de forma compartilhada na mesma infraestrutura.
O maior vetor de vulnerabilidade em APIs financeiras é o BOLA (Broken Object Level Authorization / IDOR), onde um cliente autenticado como Merchant A consegue visualizar ou modificar transações do Merchant B simplesmente enviando o UUID de outro pagamento.

## Decisão

1. **OAuth2 Resource Server com Keycloak**:
   - A API valida tokens JWT emitidos pelo Keycloak contra o JWKS público do realm (`payments`).
   - A autorização é baseada em escopos granulares da especificação OAuth2:
     - `SCOPE_payments:write`: Criar e autorizar pagamentos.
     - `SCOPE_payments:read`: Consultar pagamentos e histórico.
     - `SCOPE_payments:admin`: Liquidações financeiras e operações sensíveis (`/settle`).

2. **Identificação e Contexto do Merchant**:
   - A claim `merchant_id` (com fallback para `sub`) é extraída do token JWT validado através da porta de infraestrutura `CurrentMerchant`.
   - As camadas de domínio e aplicação permanecem agnósticas ao Spring Security (ADR 0001), recebendo apenas o `merchant_id` resolvido.

3. **Isolamento de Dados no Banco e Prevenção Ativa de BOLA**:
   - Todas as tabelas (`payments`, `outbox_events`) incluem a coluna `merchant_id`.
   - A unicidade da chave de idempotência é isolada por tenant: `UNIQUE (merchant_id, idempotency_key)`.
   - Todas as operações de leitura e transição validam o tenant. Se um usuário autenticado tentar consultar ou transicionar um pagamento de outro tenant, a API responde com **HTTP 404 Not Found** (em vez de 403 Forbidden).
   - O uso de 404 impede a enumeração de IDs existentes e vaza zero informação sobre a existência de pagamentos de outros merchants.

## Consequências

- **(+) Conformidade com OWASP API #1**: BOLA mitigado por design tanto no nível de consulta SQL quanto no controlador REST.
- **(+) Tenants Isolados**: Dois merchants diferentes podem coincidentemente utilizar a mesma `Idempotency-Key` interna (ex.: seus próprios números de pedido sequenciais) sem gerar conflitos mútuos.
- **(+) Governança e Auditoria**: Cada transação e evento de mensageria carrega a assinatura de qual merchant foi o originador.
