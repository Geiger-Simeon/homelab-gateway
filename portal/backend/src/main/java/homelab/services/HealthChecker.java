package homelab.services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import homelab.PortalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Checks health URLs on demand and caches the result.
 * <p>
 * Only the first call per URL waits for the check. After that the cached status is returned right away
 * and refreshed in the background once it's older than the TTL, so a dead service doesn't slow down page loads.
 */
@Component
public class HealthChecker {

    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient client;
    private final Duration ttl;
    private final Clock clock;
    private final Executor refresher;
    private final Map<URI, Cached> cache = new ConcurrentHashMap<>();
    private final Set<URI> refreshing = ConcurrentHashMap.newKeySet();

    @Autowired
    public HealthChecker(final PortalProperties properties) {
        this(HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                properties.healthCacheTtl(), Clock.systemUTC(), Executors.newVirtualThreadPerTaskExecutor());
    }

    HealthChecker(final HttpClient client, final Duration ttl, final Clock clock, final Executor refresher) {
        this.client = client;
        this.ttl = ttl;
        this.clock = clock;
        this.refresher = refresher;
    }

    public ServiceStatus status(final URI healthUrl) {
        final Cached cached = cache.get(healthUrl);
        if (cached == null) {
            return refresh(healthUrl);
        }
        if (!cached.checkedAt().plus(ttl).isAfter(clock.instant())) {
            refreshInBackground(healthUrl);
        }
        return cached.status();
    }

    private void refreshInBackground(final URI healthUrl) {
        // one refresh per URL at a time, parallel page loads just get the old value
        if (!refreshing.add(healthUrl)) {
            return;
        }
        try {
            refresher.execute(() -> {
                try {
                    refresh(healthUrl);
                } finally {
                    refreshing.remove(healthUrl);
                }
            });
        } catch (final RejectedExecutionException e) {
            refreshing.remove(healthUrl);
        }
    }

    private ServiceStatus refresh(final URI healthUrl) {
        final Result result = check(healthUrl);
        final Cached previous = cache.put(healthUrl, new Cached(result.status(), clock.instant()));

        // log changes only, otherwise a dead service fills the journal every 30 s
        if (previous == null || previous.status() != result.status()) {
            if (result.status() == ServiceStatus.ONLINE) {
                log.info("health check {}: online", healthUrl);
            } else {
                log.warn("health check {}: {} ({})", healthUrl, result.status(), result.reason());
            }
        }
        return result.status();
    }

    private Result check(final URI healthUrl) {
        final HttpRequest request = HttpRequest.newBuilder(healthUrl).timeout(TIMEOUT).GET().build();
        try {
            final int code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code >= 200 && code < 300) {
                return new Result(ServiceStatus.ONLINE, null);
            }
            final String hint = code >= 300 && code < 400 || code == 401
                    ? ", the URL is probably behind oauth2-proxy"
                    : "";
            return new Result(ServiceStatus.OFFLINE, "HTTP " + code + hint);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(ServiceStatus.UNKNOWN, "interrupted");
        } catch (final Exception e) {
            // e.g. SSLHandshakeException when the JVM doesn't trust the certificate
            return new Result(ServiceStatus.OFFLINE, e.toString());
        }
    }

    private record Result(ServiceStatus status, String reason) {
    }

    private record Cached(ServiceStatus status, Instant checkedAt) {
    }
}
