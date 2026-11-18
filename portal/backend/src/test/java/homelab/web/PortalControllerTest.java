package homelab.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import homelab.auth.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "portal.auth.tenant-id=11111111-1111-1111-1111-111111111111",
        "portal.auth.client-id=22222222-2222-2222-2222-222222222222",
        // local override: no network, catalog uses the override instead of the health URL
        "portal.services.nextcloud.status-override=ONLINE",
        // a service without role must stay hidden, even for someone with every role
        "portal.services.ohne-rolle.name=Ohne Rolle",
        "portal.services.ohne-rolle.host=ohne-rolle.homelab.test",
        "portal.services.ohne-rolle.url=https://ohne-rolle.homelab.test",
        "portal.services.ohne-rolle.icon=cloud",
        "portal.services.ohne-rolle.color=blue",
        "portal.services.ohne-rolle.status-override=ONLINE"
})
class PortalControllerTest {

    @TempDir
    static Path tmp;

    private static Path statusFile;

    @DynamicPropertySource
    static void statusFile(final DynamicPropertyRegistry registry) throws IOException {
        statusFile = Files.writeString(tmp.resolve("system-status.json"), fileWithFutureTimestamp());
        registry.add("portal.system-status-file", statusFile::toString);
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void resetStatusFile() throws IOException {
        Files.writeString(statusFile, fileWithFutureTimestamp());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/me", "/api/services", "/api/system"})
    void givenNoToken_whenCallingTheApi_thenUnauthorized(final String path) throws Exception {
        // given
        final var request = get(path);

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void givenNoTokenButFakedAuthHeader_whenCallingSystem_thenUnauthorized() throws Exception {
        // given: the legacy header should carry no meaning anymore
        final var request = get("/api/system").header("X-Auth-Request-Groups", "portal-admin");

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void givenAdminRole_whenGettingMe_thenAdminFlagIsSet() throws Exception {
        // given
        final var request = get("/api/me").with(admin());

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Max Mustermann"))
                .andExpect(jsonPath("$.email").value("max@homelab.test"))
                .andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void givenNoAdminRole_whenGettingMe_thenAdminFlagIsFalse() throws Exception {
        // given
        final var request = get("/api/me").with(user("app-nextcloud"));

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void givenOneRole_whenGettingServices_thenOnlyThatServiceWithoutRoleField() throws Exception {
        // given
        final var request = get("/api/services").with(user("app-nextcloud"));

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
    void givenNoMatchingRole_whenGettingServices_thenEmptyList() throws Exception {
        // given
        final var request = get("/api/services").with(user());

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void givenPlannedServiceRole_whenGettingServices_thenStatusIsPlanned() throws Exception {
        // given
        final var request = get("/api/services").with(user("app-rezepte"));

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("rezepte"))
                .andExpect(jsonPath("$[0].status").value("PLANNED"));
    }

    @Test
    void givenEveryRole_whenGettingServices_thenServiceWithoutRoleIsHidden() throws Exception {
        // given
        final var request = get("/api/services")
                .with(user("portal-admin", "app-nextcloud", "app-jellyfin", "app-rezepte", "app-kalorien"));

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].id", not(hasItem("ohne-rolle"))));
    }

    @Test
    void givenNoAdminRole_whenGettingSystem_thenForbidden() throws Exception {
        // given
        final var request = get("/api/system").with(user("app-nextcloud"));

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isForbidden());
    }

    @Test
    void givenAdminRole_whenGettingSystem_thenStatusIsReturnedWithNoStore() throws Exception {
        // given
        final var request = get("/api/system").with(admin());

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.generatedAt").value("2999-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.checks[0].state").value("OK"));
    }

    @Test
    void givenBrokenStatusFile_whenGettingSystem_thenNotFound() throws Exception {
        // given
        Files.writeString(statusFile, "{\"generatedAt\":\"2026-11-15T12:00:00Z\",\"checks\":[{\"id\":");
        final var request = get("/api/system").with(admin());

        // when
        final var result = mvc.perform(request);

        // then
        result.andExpect(status().isNotFound());
    }

    private static JwtRequestPostProcessor admin() {
        return user("portal-admin");
    }

    // jwt() skips the decoder, the authorities still go through the real roles converter
    private static JwtRequestPostProcessor user(final String... roles) {
        return jwt()
                .jwt(j -> j
                        .claim("name", "Max Mustermann")
                        .claim("preferred_username", "max@homelab.test")
                        .claim("email", "max@homelab.test")
                        .claim(SecurityConfig.ROLES_CLAIM, List.of(roles)))
                .authorities(SecurityConfig.rolesConverter());
    }

    private static String fileWithFutureTimestamp() {
        return """
                {"generatedAt":"2999-01-01T00:00:00Z","checks":[
                  {"id":"tunnel","label":"WireGuard-Tunnel","value":"Verbunden","state":"OK"}]}""";
    }
}
