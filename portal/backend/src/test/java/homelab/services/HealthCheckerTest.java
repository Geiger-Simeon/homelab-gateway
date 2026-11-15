package homelab.services;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HealthCheckerTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private HttpServer server;
    private final AtomicInteger code = new AtomicInteger(200);
    private final AtomicInteger hits = new AtomicInteger();
    private final MutableClock clock = new MutableClock();
    private final List<Runnable> background = new ArrayList<>();
    private HealthChecker checker;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(code.get(), -1);
            exchange.close();
        });
        server.start();

        final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
        // background refreshes are collected and run by the test
        checker = new HealthChecker(client, TTL, clock, background::add);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/status");
    }

    @Test
    void givenHttp200_whenChecking_thenOnline() {
        // given
        code.set(200);

        // when
        final ServiceStatus status = checker.status(url());

        // then
        assertThat(status).isEqualTo(ServiceStatus.ONLINE);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {302, 401, 500})
    void givenOtherHttpCode_whenChecking_thenOffline(final int httpCode) {
        // given
        code.set(httpCode);

        // when
        final ServiceStatus status = checker.status(url());

        // then
        assertThat(status).isEqualTo(ServiceStatus.OFFLINE);
    }

    @Test
    void givenUnreachableService_whenChecking_thenOffline() {
        // given
        final URI dead = url();
        server.stop(0);

        // when
        final ServiceStatus status = checker.status(dead);

        // then
        assertThat(status).isEqualTo(ServiceStatus.OFFLINE);
    }

    @Test
    void givenCachedEntryWithinTtl_whenChecking_thenNoNewRequest() {
        // given
        checker.status(url());
        clock.advance(TTL.minusSeconds(1));

        // when
        checker.status(url());

        // then
        assertThat(hits).hasValue(1);
        assertThat(background).isEmpty();
    }

    @Test
    void givenStaleEntry_whenCheckingTwice_thenOldValueAndOneRefreshQueued() {
        // given
        checker.status(url());
        code.set(500);
        clock.advance(TTL);

        // when
        final ServiceStatus first = checker.status(url());
        final ServiceStatus second = checker.status(url());

        // then
        assertThat(first).isEqualTo(ServiceStatus.ONLINE);
        assertThat(second).isEqualTo(ServiceStatus.ONLINE);
        assertThat(background).hasSize(1);
        assertThat(hits).hasValue(1);
    }

    @Test
    void givenQueuedRefresh_whenItRuns_thenNewValueIsCached() {
        // given
        checker.status(url());
        code.set(500);
        clock.advance(TTL);
        checker.status(url());

        // when
        background.removeFirst().run();

        // then
        assertThat(hits).hasValue(2);
        assertThat(checker.status(url())).isEqualTo(ServiceStatus.OFFLINE);
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-11-15T12:00:00Z");

        void advance(final Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }
    }
}
