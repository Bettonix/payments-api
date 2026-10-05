package com.portfolio.payments.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.payments.application.port.EventSerializer;
import com.portfolio.payments.domain.PaymentEvent;
import org.springframework.stereotype.Component;

/**
 * Implementação Jackson da porta {@link EventSerializer}.
 * Garante serialização e escape JSON adequados (resolução C3).
 */
@Component
public class JacksonEventSerializer implements EventSerializer {

    private final ObjectMapper objectMapper;

    public JacksonEventSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(PaymentEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event " + event.eventType(), e);
        }
    }
}
