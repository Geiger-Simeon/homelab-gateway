package homelab.auth;

import java.util.Collection;
import java.util.List;

import homelab.PortalProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Validates the Entra ID token nginx forwards as a Bearer. No session, loopback binding stays as the
 * second layer. CSRF stays on for requests without a token; Spring skips it for Bearer requests, the
 * oauth2-proxy cookie in front of them is SameSite=lax.
 */
@Configuration
public class SecurityConfig {

    public static final String ROLES_CLAIM = "roles";

    private final PortalProperties properties;

    public SecurityConfig(final PortalProperties properties) {
        this.properties = properties;
    }

    @Bean
    SecurityFilterChain filterChain(final HttpSecurity http) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/system").hasRole(properties.adminRole())
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(authenticationConverter())));
        return http.build();
    }

    @Bean
    @Profile("!dev")
    JwtDecoder jwtDecoder() {
        final String tenantId = properties.auth().tenantId();
        final String clientId = properties.auth().clientId();
        if (tenantId == null || tenantId.isBlank() || clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("portal.auth.tenant-id and portal.auth.client-id must be set");
        }
        // withJwkSetUri keeps the keys lazy, so the app can start without network
        final NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.auth().jwkSetUri()).build();
        decoder.setJwtValidator(tokenValidator(properties.auth().issuer(), clientId));
        return decoder;
    }

    /**
     * Default validators (exp, nbf) plus issuer and aud. Exposed so the tests can call it.
     */
    public static OAuth2TokenValidator<Jwt> tokenValidator(final String issuer, final String clientId) {
        return JwtValidators.createDefaultWithValidators(
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(clientId)));
    }

    /**
     * Turns the Entra "roles" claim into Spring ROLE_* authorities. Static so the tests reuse the same mapping.
     */
    public static Converter<Jwt, Collection<GrantedAuthority>> rolesConverter() {
        final JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        return authorities;
    }

    private static JwtAuthenticationConverter authenticationConverter() {
        final JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesConverter());
        return converter;
    }
}
