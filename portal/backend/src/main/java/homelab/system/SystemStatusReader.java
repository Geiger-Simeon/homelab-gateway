package homelab.system;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import homelab.PortalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The status file is written by a systemd timer running as root (apt, backup, tunnel).
 * The portal itself only reads it, so it doesn't need any privileges.
 * <p>
 * The file is parsed instead of passed through, so a half-written or broken file never reaches the frontend.
 */
@Component
public class SystemStatusReader {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusReader.class);
    private static final int MAX_BYTES = 64 * 1024;

    private final Path file;
    private final Duration maxAge;
    private final JsonMapper json;
    private final Clock clock;

    @Autowired
    public SystemStatusReader(final PortalProperties properties, final JsonMapper json) {
        this(properties, json, Clock.systemUTC());
    }

    SystemStatusReader(final PortalProperties properties, final JsonMapper json, final Clock clock) {
        final String configured = properties.systemStatusFile();
        this.file = configured == null || configured.isBlank() ? null : Path.of(configured);
        this.maxAge = properties.systemStatusMaxAge();
        this.json = json;
        this.clock = clock;
    }

    /**
     * Empty if the file is missing, too large or doesn't match the expected format.
     */
    public Optional<SystemStatus> read() {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }

        final byte[] content;
        try (InputStream in = Files.newInputStream(file)) {
            content = in.readNBytes(MAX_BYTES + 1);
        } catch (final IOException e) {
            log.warn("can't read {}: {}", file, e.toString());
            return Optional.empty();
        }
        if (content.length > MAX_BYTES) {
            log.warn("{} is larger than {} bytes, ignored", file, MAX_BYTES);
            return Optional.empty();
        }

        final StatusFile parsed;
        try {
            parsed = json.readValue(content, StatusFile.class);
        } catch (final RuntimeException e) {
            log.warn("{} is not valid JSON for the status file: {}", file, e.getMessage());
            return Optional.empty();
        }

        final String problem = problem(parsed);
        if (problem != null) {
            log.warn("{} ignored: {}", file, problem);
            return Optional.empty();
        }

        final boolean stale = parsed.generatedAt().plus(maxAge).isBefore(clock.instant());
        return Optional.of(new SystemStatus(parsed.generatedAt(), stale, List.copyOf(parsed.checks())));
    }

    private static String problem(final StatusFile parsed) {
        if (parsed == null || parsed.generatedAt() == null) {
            return "generatedAt is missing";
        }
        if (parsed.checks() == null) {
            return "checks is missing";
        }
        for (final SystemStatus.Check c : parsed.checks()) {
            if (c == null || c.id() == null || c.label() == null || c.value() == null || c.state() == null) {
                return "every check needs id, label, value and state";
            }
        }
        return null;
    }

    // layout of the file on disk, stale is computed here
    record StatusFile(Instant generatedAt, List<SystemStatus.Check> checks) {
    }
}
