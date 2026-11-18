package homelab.web;

import java.util.List;

import homelab.PortalProperties;
import homelab.auth.CurrentUser;
import homelab.services.ServiceCatalog;
import homelab.services.ServiceView;
import homelab.system.SystemStatus;
import homelab.system.SystemStatusReader;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PortalController {

    private final ServiceCatalog catalog;
    private final SystemStatusReader system;
    private final String adminRole;

    public PortalController(final ServiceCatalog catalog, final SystemStatusReader system,
                            final PortalProperties properties) {
        this.catalog = catalog;
        this.system = system;
        this.adminRole = properties.adminRole();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal final Jwt jwt) {
        final CurrentUser user = CurrentUser.from(jwt);
        return new MeResponse(user.name(), user.email(), user.hasRole(adminRole));
    }

    @GetMapping("/services")
    public List<ServiceView> services(@AuthenticationPrincipal final Jwt jwt) {
        return catalog.visibleFor(CurrentUser.from(jwt));
    }

    @GetMapping("/system")
    public ResponseEntity<SystemStatus> system() {
        return system.read()
                .map(status -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record MeResponse(String name, String email, boolean admin) {
    }
}
