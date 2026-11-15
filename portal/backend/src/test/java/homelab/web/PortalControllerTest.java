package homelab.web;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import homelab.PortalProperties;
import homelab.PortalProperties.ServiceDefinition;
import homelab.auth.CurrentUserResolver;
import homelab.services.HealthChecker;
import homelab.services.ServiceCatalog;
import homelab.services.ServiceStatus;
import homelab.system.SystemStatusReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PortalControllerTest {

    // recent enough to not be stale for a long time, the clock in the reader is the real one
    private static final String STATUS_JSON = """
            {"generatedAt":"2999-01-01T00:00:00Z","checks":[
              {"id":"tunnel","label":"WireGuard-Tunnel","value":"Verbunden","state":"OK"}]}""";

    @TempDir
    Path tmp;

    private Path statusFile;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        statusFile = Files.writeString(tmp.resolve("system-status.json"), STATUS_JSON);

        // no health URLs here, so the tests never touch the network
        final var services = new LinkedHashMap<String, ServiceDefinition>();
        services.put("nextcloud", service("nextcloud", "app-nextcloud", false, ServiceStatus.ONLINE));
        services.put("rezepte", service("rezepte", "app-rezepte", true, null));
        services.put("ohne-rolle", service("ohne-rolle", null, false, ServiceStatus.ONLINE));
        final var props = new PortalProperties("portal-admin", statusFile.toString(), null, null,
                new PortalProperties.Auth(null, null, null, null), services);

        final var controller = new PortalController(
                new ServiceCatalog(props, new HealthChecker(props)),
                new SystemStatusReader(props, JsonMapper.builder().build()),
                props);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AuthInterceptor(new CurrentUserResolver(props, new MockEnvironment())))
                .build();
    }

    private static ServiceDefinition service(final String id, final String role, final boolean planned,
                                             final ServiceStatus override) {
        return new ServiceDefinition(id, "", id + ".homelab.test",
                URI.create("https://" + id + ".homelab.test"), "cloud", "blue", role, planned, null, override);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/me", "/api/services", "/api/system"})
    void givenNoProxyHeaders_whenCallingTheApi_thenUnauthorized(final String path) throws Exception {
        // given
        final var request = get(path);

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void givenAdminRole_whenGettingMe_thenAdminFlagIsSet() throws Exception {
        // given
        final var request = get("/api/me")
                .header("X-Auth-Request-Email", "max@homelab.test")
                .header("X-Auth-Request-Groups", "portal-admin");

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Max"))
                .andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void givenOneRole_whenGettingServices_thenOnlyThatServiceWithoutRoleField() throws Exception {
        // given
        final var request = get("/api/services")
                .header("X-Auth-Request-Email", "gast@homelab.test")
                .header("X-Auth-Request-Groups", "app-nextcloud");

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("nextcloud"))
                .andExpect(jsonPath("$[0].status").value("ONLINE"))
                .andExpect(jsonPath("$[0].role").doesNotExist());
    }

    @Test
    void givenPlannedService_whenGettingServices_thenStatusIsPlanned() throws Exception {
        // given
        final var request = get("/api/services")
                .header("X-Auth-Request-Email", "max@homelab.test")
                .header("X-Auth-Request-Groups", "app-rezepte");

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(jsonPath("$[0].status").value("PLANNED"));
    }

    @Test
    void givenNoAdminRole_whenGettingSystem_thenForbidden() throws Exception {
        // given
        final var request = get("/api/system")
                .header("X-Auth-Request-Email", "gast@homelab.test")
                .header("X-Auth-Request-Groups", "app-nextcloud");

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isForbidden());
    }

    @Test
    void givenAdminRole_whenGettingSystem_thenStatusIsReturned() throws Exception {
        // given
        final var request = get("/api/system")
                .header("X-Auth-Request-Email", "max@homelab.test")
                .header("X-Auth-Request-Groups", "portal-admin");

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").value("2999-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.checks[0].state").value("OK"));
    }

    @Test
    void givenBrokenStatusFile_whenGettingSystem_thenNotFound() throws Exception {
        // given
        Files.writeString(statusFile, "{\"generatedAt\":\"2026-11-15T12:00:00Z\",\"checks\":[{\"id\":");
        final var request = get("/api/system")
                .header("X-Auth-Request-Email", "max@homelab.test")
                .header("X-Auth-Request-Groups", "portal-admin");

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isNotFound());
    }
}
