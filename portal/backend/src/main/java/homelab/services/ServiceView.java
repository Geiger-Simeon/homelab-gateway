package homelab.services;

import homelab.PortalProperties.ServiceDefinition;

/**
 * What the frontend gets. Role and health URL stay on the server.
 */
public record ServiceView(
        String id,
        String name,
        String description,
        String host,
        String url,
        String icon,
        String color,
        ServiceStatus status) {

    static ServiceView of(final String id, final ServiceDefinition s, final ServiceStatus status) {
        return new ServiceView(id, s.name(), s.description(), s.host(),
                s.url() != null ? s.url().toString() : null, s.icon(), s.color(), status);
    }
}
