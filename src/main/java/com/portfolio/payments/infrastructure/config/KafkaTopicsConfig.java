package com.portfolio.payments.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Configuração automática dos tópicos Kafka do domínio de pagamentos (KRaft).
 */
@Configuration
public class KafkaTopicsConfig {

    public static final String PAYMENTS_EVENTS_TOPIC = "payments.events";
    public static final String PAYMENTS_DLQ_TOPIC = "payments.dlq";

    @Bean
    public NewTopic paymentsEventsTopic() {
        return TopicBuilder.name(PAYMENTS_EVENTS_TOPIC)
            .partitions(3)
            .replicas(1)
            .build();
    }

    @Bean
    public NewTopic paymentsDlqTopic() {
        return TopicBuilder.name(PAYMENTS_DLQ_TOPIC)
            .partitions(3)
            .replicas(1)
            .build();
    }
}
