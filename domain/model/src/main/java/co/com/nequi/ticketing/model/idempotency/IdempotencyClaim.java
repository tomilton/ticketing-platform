package co.com.nequi.ticketing.model.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of claiming an idempotency key.
 */
public sealed interface IdempotencyClaim {

    /**
     * The request now holds the key.
     *
     * @param lockToken the token that proves the ownership of the key
     */
    record Granted(UUID lockToken) implements IdempotencyClaim {

        public Granted {
            Objects.requireNonNull(lockToken, "Lock token must not be null");
        }
    }

    /**
     * The key already exists and belongs to another request or to a closed one.
     *
     * @param record the stored state of the key
     */
    record Existing(IdempotencyRecord record) implements IdempotencyClaim {

        public Existing {
            Objects.requireNonNull(record, "Record must not be null");
        }
    }
}
