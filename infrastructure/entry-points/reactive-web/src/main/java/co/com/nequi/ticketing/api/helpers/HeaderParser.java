package co.com.nequi.ticketing.api.helpers;

import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.shared.CustomerId;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.UUID;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.MISSING_IDEMPOTENCY_KEY;

/**
 * Parses the identity headers and the order path parameter. Every value must be a UUID, validated
 * with {@link UUID#fromString} and a round trip, never with a regular expression.
 */
@UtilityClass
public class HeaderParser {

    public static final String CUSTOMER_ID = "X-Customer-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String ORDER_ID = "orderId";
    private static final String UUID_RULE = "must be a UUID";

    public static CustomerId parseCustomerId(String value) {
        return toUuid(value).map(CustomerId::new).orElseThrow(() -> new ValidationException(CUSTOMER_ID, UUID_RULE));
    }

    public static IdempotencyKey parseIdempotencyKey(String value) {
        return toUuid(value).map(IdempotencyKey::new).orElseThrow(MISSING_IDEMPOTENCY_KEY::build);
    }

    public static OrderId parseOrderId(String value) {
        return toUuid(value).map(OrderId::new).orElseThrow(() -> new ValidationException(ORDER_ID, UUID_RULE));
    }

    private static Optional<UUID> toUuid(String value) {
        try {
            return Optional.ofNullable(value)
                    .map(UUID::fromString)
                    .filter(uuid -> uuid.toString().equalsIgnoreCase(value));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }
}
