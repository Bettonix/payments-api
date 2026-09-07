package com.portfolio.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * payments-api — entrypoint.
 *
 * <p>API de processamento de pagamentos com idempotência, saga pattern,
 * circuit breaker e observability nativa.</p>
 */
@SpringBootApplication
@EnableScheduling
public class PaymentsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsApiApplication.class, args);
    }
}