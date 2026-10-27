package co.com.nequi.ticketing.dynamodb.support;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

import java.util.UUID;

/**
 * Allows {@link UUID#randomUUID()} in non-blocking threads. The first call seeds
 * {@code SecureRandom} from the operating system, a one-off read that BlockHound would report.
 */
public class UuidBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside(UUID.class.getName(), "randomUUID");
    }
}
