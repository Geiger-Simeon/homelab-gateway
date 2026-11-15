package homelab.web;

import java.util.List;

import homelab.PortalProperties;
import homelab.auth.CurrentUser;
import homelab.services.ServiceCatalog;
import homelab.services.ServiceView;
import homelab.system.SystemStatus;
import homelab.system.SystemStatusReader;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    public MeResponse me(@RequestAttribute(AuthInterceptor.CURRENT_USER) final CurrentUser user) {
        return new MeResponse(user.name(), user.email(), user.hasRole(adminRole));
    }

    @GetMapping("/services")
    public List<ServiceView> services(@RequestAttribute(AuthInterceptor.CURRENT_USER) final CurrentUser user) {
        return catalog.visibleFor(user);
    }

    @GetMapping("/system")
    public ResponseEntity<SystemStatus> system(@RequestAttribute(AuthInterceptor.CURRENT_USER) final CurrentUser user) {
        if (!user.hasRole(adminRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return system.read()
                .map(status -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record MeResponse(String name, String email, boolean admin) {
    }
}
