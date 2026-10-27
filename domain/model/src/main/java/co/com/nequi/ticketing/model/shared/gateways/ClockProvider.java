package co.com.nequi.ticketing.model.shared.gateways;

import java.time.Instant;

/**
 * Source of the current time, replaceable in tests.
 */
public interface ClockProvider {

    /**
     * Returns the current instant.
     *
     * @return the current instant in UTC
     */
    Instant now();
}
