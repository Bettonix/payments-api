package com.portfolio.payments.application.port;

/**
 * Porta hexagonal para obtenção da identidade do lojista (merchant) corrente.
 * Mantém a camada application desacoplada do framework Spring Security.
 */
public interface CurrentMerchant {
    String getMerchantId();
}
