package homelab.auth;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ISSUER = "https://login.microsoftonline.com/" + TENANT + "/v2.0";

    private static Jwt.Builder token() {
        // tests run at an unknown real-world date, so keep this token valid for a long time
        final Instant now = Instant.now();
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .header("typ", "JWT")
                .issuedAt(now.minusSeconds(60))
                .notBefore(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(3600));
    }

    @Test
    void givenMatchingIssuerAndAudience_whenValidating_thenNoErrors() {
        // given
        final OAuth2TokenValidator<Jwt> validator = SecurityConfig.tokenValidator(ISSUER, CLIENT);
        final Jwt jwt = token()
                .issuer(ISSUER)
                .audience(List.of(CLIENT))
                .build();

        // when
        final OAuth2TokenValidatorResult result = validator.validate(jwt);

        // then
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void givenWrongAudience_whenValidating_thenError() {
        // given
        final OAuth2TokenValidator<Jwt> validator = SecurityConfig.tokenValidator(ISSUER, CLIENT);
        final Jwt jwt = token()
                .issuer(ISSUER)
                .audience(List.of("00000000-0000-0000-0000-000000000000"))
                .build();

        // when
        final OAuth2TokenValidatorResult result = validator.validate(jwt);

        // then
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void givenWrongIssuer_whenValidating_thenError() {
        // given
        final OAuth2TokenValidator<Jwt> validator = SecurityConfig.tokenValidator(ISSUER, CLIENT);
        final Jwt jwt = token()
                .issuer("https://evil.example.com/" + TENANT + "/v2.0")
                .audience(List.of(CLIENT))
                .build();

        // when
        final OAuth2TokenValidatorResult result = validator.validate(jwt);

        // then
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void givenExpiredToken_whenValidating_thenError() {
        // given
        final OAuth2TokenValidator<Jwt> validator = SecurityConfig.tokenValidator(ISSUER, CLIENT);
        final Instant past = Instant.parse("2000-01-01T00:00:00Z");
        final Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .header("typ", "JWT")
                .issuedAt(past)
                .notBefore(past)
                .expiresAt(past.plusSeconds(60))
                .issuer(ISSUER)
                .audience(List.of(CLIENT))
                .build();

        // when
        final OAuth2TokenValidatorResult result = validator.validate(jwt);

        // then
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(OAuth2Error::getErrorCode).contains("invalid_token");
    }

    @Test
    void givenRolesClaim_whenConverting_thenRolePrefixedAuthorities() {
        // given
        final Jwt jwt = token()
                .issuer(ISSUER)
                .audience(List.of(CLIENT))
                .claims(c -> c.putAll(Map.of(
                        "sub", "u",
                        "roles", List.of("portal-admin", "app-nextcloud"))))
                .build();

        // when
        final Collection<GrantedAuthority> authorities = SecurityConfig.rolesConverter().convert(jwt);

        // then
        assertThat(authorities).contains(
                new SimpleGrantedAuthority("ROLE_portal-admin"),
                new SimpleGrantedAuthority("ROLE_app-nextcloud"));
    }
}
