package homelab.services;

import java.util.List;
import java.util.Map;

import homelab.PortalProperties;
import homelab.PortalProperties.ServiceDefinition;
import homelab.auth.CurrentUser;
import org.springframework.stereotype.Service;

@Service
public class ServiceCatalog {

    private final Map<String, ServiceDefinition> services;
    private final HealthChecker health;

    public ServiceCatalog(final PortalProperties properties, final HealthChecker health) {
        this.services = properties.services();
        this.health = health;
    }

    /**
     * Only lists what the user may open. Access itself is enforced by nginx/oauth2-proxy
     * per hostname, this list just mirrors it.
     */
    public List<ServiceView> visibleFor(final CurrentUser user) {
        return services.entrySet().stream()
                .filter(e -> user.hasRole(e.getValue().role()))
                .map(e -> ServiceView.of(e.getKey(), e.getValue(), statusOf(e.getValue())))
                .toList();
    }

    private ServiceStatus statusOf(final ServiceDefinition service) {
        if (service.planned()) {
            return ServiceStatus.PLANNED;
        }
        if (service.statusOverride() != null) {
            return service.statusOverride();
        }
        if (service.healthUrl() == null) {
            return ServiceStatus.UNKNOWN;
        }
        return health.status(service.healthUrl());
    }
}
