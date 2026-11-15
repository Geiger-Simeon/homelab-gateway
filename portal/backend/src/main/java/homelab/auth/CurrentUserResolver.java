package homelab.auth;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import homelab.PortalProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Reads the signed-in user from the headers nginx copies out of the oauth2-proxy response.
 * <p>
 * Only safe because the app listens on 127.0.0.1 and nginx always overwrites these headers,
 * so a client can't send its own.
 */
@Component
public class CurrentUserResolver {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserResolver.class);
    private static final String GUEST_MARKER = "#EXT#";

    private final PortalProperties.Auth auth;

    public CurrentUserResolver(final PortalProperties properties, final Environment environment) {
        this.auth = properties.auth();
        if (auth.devUser().enabled()) {
            // the dev user is admin, it must never end up on the Pi by accident
            if (!environment.acceptsProfiles(Profiles.of("dev"))) {
                throw new IllegalStateException("portal.auth.dev-user.enabled only works with the dev profile");
            }
            log.warn("portal.auth.dev-user is enabled, every request without auth headers is '{}'",
                    auth.devUser().name());
        }
    }

    public Optional<CurrentUser> resolve(final HttpServletRequest request) {
        final String login = trimToNull(request.getHeader(auth.nameHeader()));
        final String email = trimToNull(request.getHeader(auth.emailHeader()));

        if (login == null && email == null) {
            return devUser();
        }

        final String name = displayName(login != null ? login : email);
        return Optional.of(new CurrentUser(name, email != null ? email : login,
                parseRoles(request.getHeader(auth.rolesHeader()))));
    }

    private Optional<CurrentUser> devUser() {
        final PortalProperties.DevUser dev = auth.devUser();
        if (!dev.enabled()) {
            return Optional.empty();
        }
        return Optional.of(new CurrentUser(dev.name(), dev.email(), new LinkedHashSet<>(dev.roles())));
    }

    static Set<String> parseRoles(final String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static String displayName(final String login) {
        String local = login.contains("@") ? login.substring(0, login.indexOf('@')) : login;

        final int guest = local.indexOf(GUEST_MARKER);
        if (guest >= 0) {
            local = local.substring(0, guest);
            final int at = local.lastIndexOf('_');
            if (at > 0) {
                local = local.substring(0, at);
            }
        }

        final String name = Arrays.stream(local.split("[._-]+"))
                .filter(p -> !p.isEmpty())
                .map(p -> p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1))
                .collect(Collectors.joining(" "));
        return name.isEmpty() ? login : name;
    }

    private static String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
