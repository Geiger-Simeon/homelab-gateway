package homelab.system;

import java.time.Instant;
import java.util.List;

/**
 * What /api/system returns. {@code stale} is set when the timer hasn't written the file for a while.
 */
public record SystemStatus(Instant generatedAt, boolean stale, List<Check> checks) {

    public record Check(String id, String label, String value, State state) {
    }

    public enum State {
        OK,
        WARN,
        FAIL,
        INFO
    }
}
