# ADR 0001: arquitetura hexagonal com Spring Boot

- **Data**: 2026-09-21
- **Status**: aceito

## Contexto

payments-api precisa ser testável em unit (sem subir contexto Spring), fácil de evoluir sem
acoplar a uma stack, e simples de raciocinar sobre invariantes de domínio.

## Decisão

Adotar Hexagonal Architecture (Ports & Adapters) sobre Spring Boot 3.3 + JPA.

- `domain/` contém agregado `Payment`, value objects (`Money`), e **portas** (`PaymentRepository`).
- `application/` contém use cases (`CreatePaymentUseCase`).
- `infrastructure/` contém adaptadores JPA, clients externos, beans Spring.
- `interfaces/` contém REST controllers e DTOs de borda.

Domínio **não importa** nada de `org.springframework.*` ou `jakarta.persistence.*`.

## Consequências

- (+) testes de domínio rodam sem subir contexto Spring (sub-segundo).
- (+) trocar JPA por outro mecanismo (ex.: JDBC puro) não toca use case.
- (+) leitura clara: "se está em `domain/`, é regra de negócio".
- (-) boilerplate extra (entity ↔ domain mapping).
- (-) convenção precisa ser seguida pelo time; code review tem que cobrar.

## Alternativas consideradas

- **Spring Data REST puro**: violaria invariantes de domínio (controller gerado aceita mutação direta).
- **Microservices + Spring Cloud**: overkill pra escopo de portfólio.