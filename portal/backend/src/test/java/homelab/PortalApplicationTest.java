package homelab;

import homelab.services.ServiceStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

class PortalApplicationTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    class DefaultProfile {

        @Autowired
        PortalProperties properties;

        @Test
        void givenDefaultProfile_whenReadingProperties_thenServicesAreBound() {
            // given

            // when
            final var services = properties.services();
            final var devUser = properties.auth().devUser();

            // then
            assertThat(services).containsOnlyKeys("nextcloud", "jellyfin", "rezepte", "kalorien");
            assertThat(services.keySet()).containsExactly("nextcloud", "jellyfin", "rezepte", "kalorien");
            assertThat(services.get("nextcloud").healthUrl()).hasHost("cloud.homelab.test");
            assertThat(services.get("nextcloud").statusOverride()).isNull();
            assertThat(services.get("jellyfin").planned()).isTrue();
            assertThat(devUser.enabled()).isFalse();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @ActiveProfiles("dev")
    class DevProfile {

        @Autowired
        PortalProperties properties;

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
            assertThat(nextcloud.name()).isEqualTo("Nextcloud");
            assertThat(nextcloud.role()).isEqualTo("app-nextcloud");
            assertThat(devUser.enabled()).isTrue();
        }
    }
}
