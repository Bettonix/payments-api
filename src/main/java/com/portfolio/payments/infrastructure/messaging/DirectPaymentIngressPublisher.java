package com.portfolio.payments.infrastructure.messaging;

import com.portfolio.payments.application.PaymentIngressService;
import com.portfolio.payments.application.port.PaymentIngressCommand;
import com.portfolio.payments.application.port.PaymentIngressPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador de fallback para persistência síncrona/direta quando Kafka está desativado (ex: perfil de teste).
 */
@Component
@ConditionalOnProperty(name = "app.ingress.publisher", havingValue = "direct")
public class DirectPaymentIngressPublisher implements PaymentIngressPublisher {

    private static final Logger log = LoggerFactory.getLogger(DirectPaymentIngressPublisher.class);

    private final PaymentIngressService ingressService;

    public DirectPaymentIngressPublisher(PaymentIngressService ingressService) {
        this.ingressService = ingressService;
    }

    @Override
    public void publish(PaymentIngressCommand command) {
        log.debug("direct synchronous payment ingress dispatch for paymentId={}", command.paymentId());
        ingressService.process(command);
    }
}
