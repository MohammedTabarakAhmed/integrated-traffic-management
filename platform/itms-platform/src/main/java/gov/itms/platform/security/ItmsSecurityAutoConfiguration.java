package gov.itms.platform.security;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token security for every ITMS service (SEC-001, CC-001, INT-004). Deny by default: every request
 * except health probes needs a valid token; endpoint permissions are enforced with {@code @PreAuthorize}.
 */
@AutoConfiguration
@EnableConfigurationProperties(ItmsSecurityProperties.class)
@EnableMethodSecurity
public class ItmsSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain itmsApiSecurity(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless bearer-token API; no cookies are used
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter itmsJwtAuthenticationConverter(ItmsSecurityProperties properties) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new PermissionsAuthoritiesConverter(properties.permissionsClaim()));
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder itmsJwtDecoder(ItmsSecurityProperties properties) {
        if (properties.issuer() == null || properties.jwkSetUri() == null) {
            throw new IllegalStateException("itms.security.issuer and itms.security.jwk-set-uri must be configured");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                audienceValidator(properties.audience())));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        OAuth2Error error = new OAuth2Error("invalid_token", "Required audience missing", null);
        return jwt -> {
            List<String> aud = jwt.getAudience();
            return aud != null && aud.contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(error);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    CurrentActorProvider currentActorProvider(ItmsSecurityProperties properties) {
        return new CurrentActorProvider(properties);
    }
}
