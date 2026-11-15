package homelab.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import homelab.PortalProperties;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserResolverTest {

    private static final PortalProperties.DevUser DEV =
            new PortalProperties.DevUser(true, "Dev", "dev@local", List.of("portal-admin"));

    private final CurrentUserResolver resolver = new CurrentUserResolver(props(null), env());

    private static PortalProperties props(final PortalProperties.DevUser dev) {
        return new PortalProperties(null, null, null, null,
                new PortalProperties.Auth(null, null, null, dev), Map.of());
    }

    private static MockEnvironment env(final String... profiles) {
        final var env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void givenAuthHeaders_whenResolving_thenUserHasNameEmailAndRoles() {
        // given
        final var request = new MockHttpServletRequest();
        request.addHeader("X-Auth-Request-Preferred-Username", "max.mustermann@homelab.test");
        request.addHeader("X-Auth-Request-Email", "max.mustermann@homelab.test");
        request.addHeader("X-Auth-Request-Groups", "app-nextcloud, portal-admin");

        // when
        final CurrentUser user = resolver.resolve(request).orElseThrow();

        // then
        assertThat(user.name()).isEqualTo("Max Mustermann");
        assertThat(user.email()).isEqualTo("max.mustermann@homelab.test");
        assertThat(user.roles()).containsExactlyInAnyOrder("app-nextcloud", "portal-admin");
    }

    @Test
    void givenNoHeaders_whenResolving_thenNoUser() {
        // given
        final var request = new MockHttpServletRequest();

        // when
        final Optional<CurrentUser> user = resolver.resolve(request);

        // then
        assertThat(user).isEmpty();
    }

    @Test
    void givenNoRolesHeader_whenResolving_thenUserHasNoRoles() {
        // given
        final var request = new MockHttpServletRequest();
        request.addHeader("X-Auth-Request-Email", "gast@homelab.test");

        // when
        final CurrentUser user = resolver.resolve(request).orElseThrow();

        // then
        assertThat(user.roles()).isEmpty();
    }

    @Test
    void givenDevUserAndNoHeaders_whenResolving_thenDevUserIsReturned() {
        // given
        final var devResolver = new CurrentUserResolver(props(DEV), env("dev"));
        final var request = new MockHttpServletRequest();

        // when
        final CurrentUser user = devResolver.resolve(request).orElseThrow();

        // then
        assertThat(user.name()).isEqualTo("Dev");
    }

    @Test
    void givenDevUserAndRealHeaders_whenResolving_thenHeadersWin() {
        // given
        final var devResolver = new CurrentUserResolver(props(DEV), env("dev"));
        final var request = new MockHttpServletRequest();
        request.addHeader("X-Auth-Request-Email", "echt@homelab.test");

        // when
        final CurrentUser user = devResolver.resolve(request).orElseThrow();

        // then
        assertThat(user.roles()).isEmpty();
    }

    @Test
    void givenDevUserWithoutProfile_whenStarting_thenItFails() {
        // given
        final var noProfile = env();

        // when
        final ThrowingCallable start = () -> new CurrentUserResolver(props(DEV), noProfile);

        // then
        assertThatThrownBy(start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void givenDevUserInProdProfile_whenStarting_thenItFails() {
        // given
        final var prod = env("prod");

        // when
        final ThrowingCallable start = () -> new CurrentUserResolver(props(DEV), prod);

        // then
        assertThatThrownBy(start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void givenMessyRoleList_whenParsing_thenBlanksAndDuplicatesAreDropped() {
        // given
        final String header = " a, ,b,a ";

        // when
        final Set<String> roles = CurrentUserResolver.parseRoles(header);

        // then
        assertThat(roles).isEqualTo(Set.of("a", "b"));
    }

    @Test
    void givenNoRoleList_whenParsing_thenNoRoles() {
        // given
        final String header = null;

        // when
        final Set<String> roles = CurrentUserResolver.parseRoles(header);

        // then
        assertThat(roles).isEmpty();
    }

    @ParameterizedTest(name = "{2}")
    @CsvSource(delimiter = '|', textBlock = """
            max.mustermann@homelab.test                      | Max Mustermann  | email login
            max_mustermann                                  | Max Mustermann | underscore login
            anna.berg_gmail.com#EXT#@tenant.onmicrosoft.com | Anna Berg      | Entra B2B guest
            @homelab.test                                   | @homelab.test  | nothing usable, keep the raw login
            ...                                             | ...            | only separators, keep the raw login
            """)
    void givenLogin_whenBuildingDisplayName_thenNameIsReadable(final String login, final String expected, final String description) {
        // given: login from the csv row

        // when
        final String name = CurrentUserResolver.displayName(login);

        // then
        assertThat(name).isEqualTo(expected);
    }
}
