package homelab;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import homelab.services.ServiceStatus;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "portal")
public record PortalProperties(
        String adminRole,
        String systemStatusFile,
        Duration systemStatusMaxAge,
        Duration healthCacheTtl,
        Auth auth,
        Map<String, ServiceDefinition> services) {

    public PortalProperties {
        if (adminRole == null || adminRole.isBlank()) {
            adminRole = "portal-admin";
        }
        if (systemStatusMaxAge == null) {
            systemStatusMaxAge = Duration.ofMinutes(15);
        }
        if (healthCacheTtl == null) {
            healthCacheTtl = Duration.ofSeconds(30);
        }
        if (auth == null) {
            auth = new Auth(null, null, null, null);
        }
        services = services == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(services));
    }

    /**
     * Names of the headers nginx sets from the oauth2-proxy auth_request response.
     */
    public record Auth(String nameHeader, String emailHeader, String rolesHeader, DevUser devUser) {

        public Auth {
            nameHeader = nameHeader == null ? "X-Auth-Request-Preferred-Username" : nameHeader;
            emailHeader = emailHeader == null ? "X-Auth-Request-Email" : emailHeader;
            rolesHeader = rolesHeader == null ? "X-Auth-Request-Groups" : rolesHeader;
            devUser = devUser == null ? new DevUser(false, null, null, null) : devUser;
        }
    }

    /**
     * Fake user for local development without oauth2-proxy. Only allowed with the dev profile.
     */
    public record DevUser(boolean enabled, String name, String email, List<String> roles) {

        public DevUser {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /**
     * One tile on the start page, the map key is its id.
     *
     * @param role           Entra app role needed to see the service
     * @param planned        shown as "in planning", no link
     * @param healthUrl      checked by the backend, any 2xx counts as online
     * @param statusOverride fixed status, wins over healthUrl
     */
    public record ServiceDefinition(
            String name,
            String description,
            String host,
            URI url,
            String icon,
            String color,
            String role,
            boolean planned,
            URI healthUrl,
            ServiceStatus statusOverride) {
    }
}
