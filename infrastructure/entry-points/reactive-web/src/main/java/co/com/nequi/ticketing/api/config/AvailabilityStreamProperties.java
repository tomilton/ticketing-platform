package co.com.nequi.ticketing.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Timing of the live availability stream, read from the {@code availability.stream} block.
 *
 * @param heartbeat   the time between two keepalive comments
 * @param maxDuration the time after which the stream asks the client to reconnect and closes
 * @param retry       the reconnection delay sent to the client
 */
@ConfigurationProperties("availability.stream")
public record AvailabilityStreamProperties(Duration heartbeat, Duration maxDuration, Duration retry) {

    public AvailabilityStreamProperties {
        Stream.of(heartbeat, maxDuration, retry).forEach(AvailabilityStreamProperties::requirePositive);
    }

    private static void requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "Stream durations must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Stream durations must be positive");
        }
    }
}
