package homelab;

import homelab.services.ServiceStatus;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PortalApplicationTest {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "portal.auth.tenant-id=11111111-1111-1111-1111-111111111111",
            "portal.auth.client-id=22222222-2222-2222-2222-222222222222"
    })
    class DefaultProfile {

        @Autowired
        PortalProperties properties;

        @Autowired
        ConfigurableApplicationContext context;

        @Test
        void givenDefaultProfile_whenReadingProperties_thenServicesAreBound() {
            // given

            // when
            final var services = properties.services();

            // then
            assertThat(services).containsOnlyKeys("nextcloud", "jellyfin", "rezepte", "kalorien");
            assertThat(services.keySet()).containsExactly("nextcloud", "jellyfin", "rezepte", "kalorien");
            assertThat(services.get("nextcloud").healthUrl()).hasHost("cloud.homelab.test");
            assertThat(services.get("nextcloud").statusOverride()).isNull();
            assertThat(services.get("jellyfin").planned()).isTrue();
        }

        @Test
        void givenDefaultProfile_whenLookingUpBearerTokenResolver_thenNoBean() {
            // given

            // when
            final var lookup = context.getBeanProvider(BearerTokenResolver.class).getIfAvailable();

            // then
            assertThat(lookup).isNull();
        }

        @Test
        void givenDefaultProfile_whenLookingUpDecoder_thenRealNimbusDecoder() {
            // given: keys are loaded on the first token, so this needs no network

            // when
            final var decoder = context.getBean(JwtDecoder.class);

            // then
            assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
        }
    }

    @Nested
    class MissingIds {

        @Test
        void givenNoTenantOrClientId_whenStartingTheContext_thenItFails() {
            // given
            final var app = new SpringApplication(PortalApplication.class);
            // command line args beat application.yml and ENTRA_* env vars from the shell
            final String[] args = {"--server.port=0", "--portal.auth.tenant-id=", "--portal.auth.client-id="};

            // when
            final ThrowingCallable start = () -> {
                try (ConfigurableApplicationContext ignored = app.run(args)) {
                    // not reached
                }
            };

            // then
            assertThatThrownBy(start).hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("dev")
    class DevProfile {

        @Autowired
        PortalProperties properties;

        @Autowired
        MockMvc mvc;

        @Test
        void givenDevProfile_whenReadingProperties_thenOverridesAreMerged() {
            // given

            // when
            final var services = properties.services();
            final var nextcloud = services.get("nextcloud");
            final var devUser = properties.auth().devUser();

            // then
            assertThat(services.keySet()).containsExactly("nextcloud", "jellyfin", "rezepte", "kalorien");
            assertThat(nextcloud.statusOverride()).isEqualTo(ServiceStatus.ONLINE);
            assertThat(nextcloud.role()).isEqualTo("app-nextcloud");
            assertThat(devUser.name()).isEqualTo("Max Mustermann");
            assertThat(devUser.roles()).contains("portal-admin");
        }

        @Test
        void givenDevProfile_whenCallingMeWithoutToken_thenDevUserIsReturnedAsAdmin() throws Exception {
            // given
            final var request = get("/api/me");

            // when
            final var result = mvc.perform(request);

            // then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Max Mustermann"))
                    .andExpect(jsonPath("$.admin").value(true));
        }
    }
}
