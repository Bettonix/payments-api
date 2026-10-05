package com.portfolio.payments.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.payments.application.PaymentIngressService;
import com.portfolio.payments.application.port.PaymentIngressCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor Kafka que recebe comandos de pagamento do tópico de ingestão,
 * processa a persistência no banco relacional e Outbox de forma assíncrona.
 */
@Component
@ConditionalOnProperty(name = "app.ingress.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentIngressConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentIngressConsumer.class);

    private final PaymentIngressService ingressService;
    private final ObjectMapper objectMapper;

    @Autowired
    public PaymentIngressConsumer(PaymentIngressService ingressService, ObjectMapper objectMapper) {
        this.ingressService = ingressService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
        topics = "${app.kafka.topics.ingress:payments.ingress}",
        groupId = "${app.kafka.ingress-group:payments-ingress-workers}",
        concurrency = "${app.kafka.ingress-concurrency:3}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            PaymentIngressCommand command = objectMapper.readValue(record.value(), PaymentIngressCommand.class);
            log.debug("consumed payment ingress command from partition={} offset={} paymentId={}",
                record.partition(), record.offset(), command.paymentId());
            ingressService.process(command);
        } catch (Exception e) {
            log.error("failed to process ingress record at partition={} offset={}: {}",
                record.partition(), record.offset(), e.getMessage(), e);
            throw new RuntimeException("Error processing ingress payment message", e);
        }
    }
}
