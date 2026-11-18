package homelab.system;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import homelab.PortalProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SystemStatusReaderTest {

    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

    @TempDir
    Path tmp;

    private SystemStatusReader reader(final Path file) {
        final var props = new PortalProperties(null, file.toString(), Duration.ofMinutes(15), null, null, Map.of());
        return new SystemStatusReader(props, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Path write(final String content) throws Exception {
        return Files.writeString(tmp.resolve("status.json"), content);
    }

    private static String file(final String generatedAt) {
        return """
                {"generatedAt":"%s","checks":[
                  {"id":"backup","label":"Letztes Backup","value":"vor 7 Std.","state":"INFO"}]}""".formatted(generatedAt);
    }

    @Test
    void givenFreshFile_whenReading_thenStatusIsNotStale() throws Exception {
        // given
        final var reader = reader(write(file("2026-11-15T11:50:00Z")));

        // when
        final SystemStatus status = reader.read().orElseThrow();

        // then
        assertThat(status.stale()).isFalse();
        assertThat(status.checks()).singleElement()
                .satisfies(c -> assertThat(c.state()).isEqualTo(SystemStatus.State.INFO));
    }

    @Test
    void givenOldFile_whenReading_thenStatusIsStale() throws Exception {
        // given
        final var reader = reader(write(file("2026-11-15T11:40:00Z")));

        // when
        final SystemStatus status = reader.read().orElseThrow();

        // then
        assertThat(status.stale()).isTrue();
    }

    @Test
    void givenMissingFile_whenReading_thenEmpty() {
        // given
        final var reader = reader(tmp.resolve("nope.json"));

        // when
        final Optional<SystemStatus> status = reader.read();

        // then
        assertThat(status).isEmpty();
    }

    static Stream<Arguments> brokenFiles() {
        final String valid = file("2026-11-15T11:50:00Z");
        return Stream.of(
                Arguments.of("truncated", "{\"generatedAt\":"),
                Arguments.of("no generatedAt", "{\"checks\":[]}"),
                Arguments.of("no checks", "{\"generatedAt\":\"2026-11-15T11:50:00Z\"}"),
                Arguments.of("unknown state", valid.replace("\"INFO\"", "\"MAYBE\"")),
                Arguments.of("check without label", valid.replace("\"label\":\"Letztes Backup\",", "")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenFiles")
    void givenBrokenFile_whenReading_thenEmpty(final String description, final String content) throws Exception {
        // given
        final var reader = reader(write(content));

        // when
        final Optional<SystemStatus> status = reader.read();

        // then
        assertThat(status).isEmpty();
    }

    @Test
    void givenTooLargeFile_whenReading_thenEmpty() throws Exception {
        // given
        final String padding = " ".repeat(64 * 1024);
        final var reader = reader(write(file("2026-11-15T11:50:00Z") + padding));

        // when
        final Optional<SystemStatus> status = reader.read();

        // then
        assertThat(status).isEmpty();
    }
}
