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
            auth = new Auth(null, null, null);
        }
        services = services == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(services));
    }

    /**
     * Entra tenant and client id of the app registration. The ID token's issuer and aud are checked
     * against these. Required outside the dev profile.
     */
    public record Auth(String tenantId, String clientId, DevUser devUser) {

        public Auth {
            devUser = devUser == null ? new DevUser(null, null, null, List.of()) : devUser;
        }

        public String issuer() {
            return "https://login.microsoftonline.com/" + tenantId + "/v2.0";
        }

        public String jwkSetUri() {
            return "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys";
        }
    }

    /**
     * Fake user for the dev profile. The profile alone turns it on, no extra flag.
     */
    public record DevUser(String name, String email, String preferredUsername, List<String> roles) {

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
