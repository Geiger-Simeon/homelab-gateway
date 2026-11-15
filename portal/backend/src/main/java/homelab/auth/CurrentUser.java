package homelab.auth;

import java.util.Set;

public record CurrentUser(String name, String email, Set<String> roles) {

    public CurrentUser {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(final String role) {
        return role != null && roles.contains(role);
    }
}
