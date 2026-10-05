package com.portfolio.payments.application.port;

import com.portfolio.payments.domain.PaymentEvent;

/**
 * Porta de serialização de eventos de domínio para o payload do outbox.
 * A implementação fica em infraestrutura usando Jackson para evitar
 * JSON manual propenso a falhas de escape (C3).
 */
public interface EventSerializer {
    String serialize(PaymentEvent event);
}
