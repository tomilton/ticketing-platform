package co.com.nequi.ticketing.config;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.model.config.IdempotencySettings;
import org.springframework.context.annotation.Configuration;

/**
 * Checks at startup that a whole reservation always ends before the lease of its idempotency key,
 * so a request that is still alive never loses its key because it is slow.
 */
@Configuration
public class TimeoutConsistencyConfig {

    public TimeoutConsistencyConfig(DynamoDbProperties dynamoDbProperties, IdempotencySettings idempotencySettings) {
        if (dynamoDbProperties.reserveTimeout().compareTo(idempotencySettings.lease()) >= 0) {
            throw new IllegalStateException("The reserve timeout must be shorter than the idempotency lease");
        }
    }
}
