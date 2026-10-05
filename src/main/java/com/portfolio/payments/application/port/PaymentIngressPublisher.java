package com.portfolio.payments.application.port;

/**
 * Porta de infraestrutura para emissão de comandos de pagamento para fila de ingestão.
 */
public interface PaymentIngressPublisher {
    void publish(PaymentIngressCommand command);
}
