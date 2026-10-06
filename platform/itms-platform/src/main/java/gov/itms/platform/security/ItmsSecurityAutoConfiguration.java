package gov.itms.platform.security;

import gov.itms.platform.observability.ManagementRequests;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * Stateless bearer-token security for every ITMS service (SEC-001, CC-001, INT-004). Deny by default: every request
 * except health probes and, on the management port only, metrics scraping ({@link ManagementRequests}) needs a valid
 * token. The token only authenticates; the {@link AuthorisationFilter} then
 * attaches the subject's grants from the configured {@link AuthorisationResolver} (ADR-022). Endpoint permissions
 * are enforced with {@code @PreAuthorize}, data scopes with {@link Authorisation}.
 */
@AutoConfiguration
@EnableConfigurationProperties(ItmsSecurityProperties.class)
@EnableMethodSecurity
public class ItmsSecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ItmsSecurityAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain itmsApiSecurity(HttpSecurity http, JwtAuthenticationConverter converter,
                                        AuthorisationResolver resolver, MessageSource messages, ObjectMapper json,
                                        ItmsSecurityProperties properties)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless bearer-token API; no cookies are used
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                            .requestMatchers(new ManagementRequests()).permitAll();
                    if (!properties.publicPaths().isEmpty()) {
                        // deliberately public pages only (CC-083); GET, no token, no grants
                        a.requestMatchers(org.springframework.http.HttpMethod.GET,
                                properties.publicPaths().toArray(String[]::new)).permitAll();
                    }
                    a.anyRequest().authenticated();
                })
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .addFilterAfter(new AuthorisationFilter(resolver, messages, json), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Authentication only: no authorities come from the token itself; the {@link AuthorisationFilter} adds them. */
    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter itmsJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    AuthorisationResolver itmsAuthorisationResolver(ItmsSecurityProperties properties, ObjectProvider<Clock> clock,
                                                    ObjectProvider<ObservationRegistry> observations) {
        if (properties.authorisationSource() == ItmsSecurityProperties.AuthorisationSource.TOKEN_CLAIMS) {
            log.warn("itms.security.authorisation-source=token-claims: permissions are read from the token (interim "
                    + "ADR-018 mode, national scope only). Production must use iam (ADR-022).");
            return new ClaimsAuthorisationResolver(properties.permissionsClaim(), properties.rolesClaim());
        }
        ItmsSecurityProperties.Iam iam = properties.iam();
        if (iam.baseUrl() == null || iam.baseUrl().isBlank()) {
            throw new IllegalStateException("itms.security.iam.base-url must be configured (authorisation-source=iam)");
        }
        return new RemoteIamAuthorisationResolver(iam.baseUrl(), iam.timeout(), iam.cacheTtl(), iam.cacheMaxEntries(),
                clock.getIfAvailable(Clock::systemUTC), observations.getIfAvailable(() -> ObservationRegistry.NOOP));
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
    CurrentActorProvider currentActorProvider() {
        return new CurrentActorProvider();
    }
}
