package homelab.auth;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.jwt.Jwt;

public record CurrentUser(String name, String email, Set<String> roles) {

    private static final String GUEST_MARKER = "#EXT#";

    public CurrentUser {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(final String role) {
        return role != null && roles.contains(role);
    }

    /**
     * Builds the view the controllers use from a validated ID token.
     */
    public static CurrentUser from(final Jwt jwt) {
        final String login = trimToNull(jwt.getClaimAsString("preferred_username"));
        final String nameClaim = trimToNull(jwt.getClaimAsString("name"));
        final String email = trimToNull(jwt.getClaimAsString("email"));
        final String sub = trimToNull(jwt.getSubject());

        final String name;
        if (nameClaim != null) {
            name = nameClaim;
        } else if (login != null) {
            name = displayName(login);
        } else {
            name = sub != null ? sub : "";
        }
        final String displayEmail = email != null ? email : (login != null ? login : "");

        return new CurrentUser(name, displayEmail, roles(jwt));
    }

    private static Set<String> roles(final Jwt jwt) {
        final List<String> raw = jwt.getClaimAsStringList(SecurityConfig.ROLES_CLAIM);
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        return raw.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(String::trim)
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
