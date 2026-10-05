package com.portfolio.payments.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.payments.application.port.PaymentIngressCommand;
import com.portfolio.payments.application.port.PaymentIngressPublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Adaptador de mensageria que despacha comandos de pagamento para o tópico Kafka de ingestão.
 * Particionado pelo payerId para garantir sequenciamento por pagador.
 */
@Component
@ConditionalOnProperty(name = "app.ingress.publisher", havingValue = "kafka", matchIfMissing = true)
public class KafkaPaymentIngressPublisher implements PaymentIngressPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaPaymentIngressPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    @Autowired
    public KafkaPaymentIngressPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                        ObjectMapper objectMapper,
                                        @Value("${app.kafka.topics.ingress:payments.ingress}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void publish(PaymentIngressCommand command) {
        try {
            String payload = objectMapper.writeValueAsString(command);
            String partitionKey = command.payerId().toString();

            ProducerRecord<String, String> record = new ProducerRecord<>(topic, partitionKey, payload);
            record.headers().add(new RecordHeader("ce_specversion", "1.0".getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("ce_type", "com.portfolio.payments.ingress.requested".getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("ce_source", "/payments-api/ingress".getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("ce_id", command.paymentId().toString().getBytes(StandardCharsets.UTF_8)));

            kafkaTemplate.send(record);
            log.debug("dispatched payment ingress command: paymentId={} payerId={}", command.paymentId(), command.payerId());
        } catch (Exception e) {
            log.error("failed to dispatch payment ingress command: paymentId={}", command.paymentId(), e);
            throw new RuntimeException("Failed to enqueue payment ingress command", e);
        }
    }
}
