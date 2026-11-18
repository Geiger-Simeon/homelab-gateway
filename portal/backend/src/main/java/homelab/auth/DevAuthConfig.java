package homelab.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import homelab.PortalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

/**
 * Fake authentication for local development: every request is the dev user. Replaces the token
 * validator but keeps the same filter chain and roles converter.
 */
@Configuration
@Profile("dev")
public class DevAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(DevAuthConfig.class);
    private static final String DEV_TOKEN = "dev";

    private final PortalProperties.DevUser devUser;

    public DevAuthConfig(final PortalProperties properties) {
        this.devUser = properties.auth().devUser();
        log.warn("dev profile is active, every request is '{}'", devUser.name());
    }

    @Bean
    BearerTokenResolver devBearerTokenResolver() {
        return request -> DEV_TOKEN;
    }

    @Bean
    JwtDecoder devJwtDecoder() {
        final Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "dev");
        claims.put("name", devUser.name());
        claims.put("preferred_username", devUser.preferredUsername());
        claims.put("email", devUser.email());
        claims.put(SecurityConfig.ROLES_CLAIM, devUser.roles());

        // fresh iat/exp per request, nothing checks them here but it reads like a real token
        return token -> {
            final Instant now = Instant.now();
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(3600))
                    .claims(c -> c.putAll(claims))
                    .build();
        };
    }
}
