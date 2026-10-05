package com.portfolio.payments.application;

import com.portfolio.payments.domain.Money;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Utilitário para cálculo de hash canônico (SHA-256) do payload da requisição.
 * Usado para detectar reutilização de chave de idempotência com valores divergentes (C8).
 */
public final class RequestFingerprint {

    private RequestFingerprint() {}

    public static String compute(UUID payerId, UUID payeeId, Money amount) {
        String canonical = payerId + "|" + payeeId + "|"
            + amount.amount().stripTrailingZeros().toPlainString() + "|"
            + amount.currency().getCurrencyCode();
        return sha256(canonical);
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
