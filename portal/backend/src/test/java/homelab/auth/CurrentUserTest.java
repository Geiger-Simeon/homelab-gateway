package homelab.auth;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserTest {

    private static Jwt.Builder builder() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.parse("2026-11-15T12:00:00Z"))
                .expiresAt(Instant.parse("2026-11-15T13:00:00Z"))
                .subject("user-sub");
    }

    @Test
    void givenNameAndEmailClaims_whenBuilding_thenUserHasBothAndRolesFromRolesClaim() {
        // given
        final Jwt jwt = builder()
                .claims(c -> c.putAll(Map.of(
                        "name", "Max Mustermann",
                        "preferred_username", "max@homelab.test",
                        "email", "max@homelab.test",
                        "roles", List.of("app-nextcloud", "portal-admin"))))
                .build();

        // when
        final CurrentUser user = CurrentUser.from(jwt);

        // then
        assertThat(user.name()).isEqualTo("Max Mustermann");
        assertThat(user.email()).isEqualTo("max@homelab.test");
        assertThat(user.roles()).containsExactlyInAnyOrder("app-nextcloud", "portal-admin");
    }

    @Test
    void givenNoNameClaim_whenBuilding_thenNameFromPreferredUsername() {
        // given
        final Jwt jwt = builder()
                .claims(c -> c.put("preferred_username", "max.mustermann@homelab.test"))
                .build();

        // when
        final CurrentUser user = CurrentUser.from(jwt);

        // then
        assertThat(user.name()).isEqualTo("Max Mustermann");
        assertThat(user.email()).isEqualTo("max.mustermann@homelab.test");
    }

    @Test
    void givenOnlySub_whenBuilding_thenNameIsSub() {
        // given
        final Jwt jwt = builder().build();

        // when
        final CurrentUser user = CurrentUser.from(jwt);

        // then
        assertThat(user.name()).isEqualTo("user-sub");
        assertThat(user.email()).isEmpty();
        assertThat(user.roles()).isEmpty();
    }

    @Test
    void givenNoRolesClaim_whenBuilding_thenRolesEmpty() {
        // given
        final Jwt jwt = builder()
                .claims(c -> c.put("preferred_username", "max@homelab.test"))
                .build();

        // when
        final CurrentUser user = CurrentUser.from(jwt);

        // then
        assertThat(user.roles()).isEmpty();
    }

    @ParameterizedTest(name = "{2}")
    @CsvSource(delimiter = '|', textBlock = """
            max.mustermann@homelab.test                     | Max Mustermann | email login
            max_mustermann                                  | Max Mustermann | underscore login
            anna.berg_gmail.com#EXT#@tenant.onmicrosoft.com | Anna Berg      | Entra B2B guest
            @homelab.test                                   | @homelab.test  | nothing usable, keep the raw login
            ...                                             | ...            | only separators, keep the raw login
            """)
    void givenLogin_whenBuildingDisplayName_thenNameIsReadable(final String login, final String expected,
                                                               final String description) {
        // given: login from the csv row

        // when
        final String name = CurrentUser.displayName(login);

        // then
        assertThat(name).isEqualTo(expected);
    }
}
