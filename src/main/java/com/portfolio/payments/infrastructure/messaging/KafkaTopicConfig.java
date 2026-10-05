package com.portfolio.payments.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentsIngressTopic(@Value("${app.kafka.topics.ingress:payments.ingress}") String topic) {
        return TopicBuilder.name(topic)
            .partitions(10)
            .replicas(1)
            .build();
    }
}
