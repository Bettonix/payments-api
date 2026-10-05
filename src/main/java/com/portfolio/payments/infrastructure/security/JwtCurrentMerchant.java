package com.portfolio.payments.infrastructure.security;

import com.portfolio.payments.application.port.CurrentMerchant;
import com.portfolio.payments.domain.Payment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Adaptador de segurança que extrai o merchant_id a partir do JWT do Keycloak.
 */
@Component
public class JwtCurrentMerchant implements CurrentMerchant {

    @Override
    public String getMerchantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Payment.DEFAULT_MERCHANT_ID;
        }

        if (auth.getPrincipal() instanceof Jwt jwt) {
            String claim = jwt.getClaimAsString("merchant_id");
            if (claim != null && !claim.isBlank()) {
                return claim;
            }
            String clientId = jwt.getClaimAsString("client_id");
            if (clientId != null && !clientId.isBlank()) {
                return clientId;
            }
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }

        return auth.getName() != null && !auth.getName().isBlank() ? auth.getName() : Payment.DEFAULT_MERCHANT_ID;
    }
}
