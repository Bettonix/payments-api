package com.portfolio.payments.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.net.URI;
import java.util.UUID;

/**
 * Configuração de segurança OAuth2 Resource Server baseada em JWT do Keycloak.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Documentação e Health/Prometheus públicos
                .requestMatchers(
                    "/actuator/health/**",
                    "/actuator/prometheus",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()

                // Escopo payments:write para criação e transições comuns
                .requestMatchers(HttpMethod.POST, "/v1/payments").hasAuthority("SCOPE_payments:write")
                .requestMatchers(HttpMethod.POST, "/v1/payments/*/authorize").hasAuthority("SCOPE_payments:write")
                .requestMatchers(HttpMethod.POST, "/v1/payments/*/capture").hasAuthority("SCOPE_payments:write")
                .requestMatchers(HttpMethod.POST, "/v1/payments/*/cancel").hasAuthority("SCOPE_payments:write")

                // Escopo payments:read para consulta
                .requestMatchers(HttpMethod.GET, "/v1/payments/*").hasAuthority("SCOPE_payments:read")

                // Escopo payments:admin para liquidação e marcação de falhas administrativas
                .requestMatchers(HttpMethod.POST, "/v1/payments/*/settle").hasAuthority("SCOPE_payments:admin")
                .requestMatchers(HttpMethod.POST, "/v1/payments/*/fail").hasAuthority("SCOPE_payments:admin")

                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(Customizer.withDefaults())
                .authenticationEntryPoint(problemAuthenticationEntryPoint())
                .accessDeniedHandler(problemAccessDeniedHandler())
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(problemAuthenticationEntryPoint())
                .accessDeniedHandler(problemAccessDeniedHandler())
            )
            .build();
    }

    @Bean
    public AuthenticationEntryPoint problemAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Authentication is required to access this resource: " + authException.getMessage()
            );
            problem.setType(URI.create("urn:problem-type:unauthorized"));
            problem.setTitle("Unauthorized");
            problem.setInstance(URI.create(request.getRequestURI()));
            problem.setProperty("traceId", UUID.randomUUID().toString());
            objectMapper.writeValue(response.getOutputStream(), problem);
        };
    }

    @Bean
    public AccessDeniedHandler problemAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "Access is denied. You lack the required OAuth2 scope: " + accessDeniedException.getMessage()
            );
            problem.setType(URI.create("urn:problem-type:forbidden"));
            problem.setTitle("Forbidden");
            problem.setInstance(URI.create(request.getRequestURI()));
            problem.setProperty("traceId", UUID.randomUUID().toString());
            objectMapper.writeValue(response.getOutputStream(), problem);
        };
    }
}
