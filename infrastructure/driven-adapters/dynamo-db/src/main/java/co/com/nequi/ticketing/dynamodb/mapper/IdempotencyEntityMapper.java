package co.com.nequi.ticketing.dynamodb.mapper;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.IdempotencyStatus;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.IdempotencyRecord;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.shared.CustomerId;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;

/**
 * Maps the items of the idempotency table to {@link IdempotencyRecord} and the definitive outcome
 * of a request to its attributes. The key of an item joins the customer and the key chosen by the
 * customer, because a key belongs to one customer.
 */
@Component
public class IdempotencyEntityMapper {

    public static final String IDEMPOTENCY_ID = "idempotencyId";
    public static final String REQUEST_HASH = "requestHash";
    public static final String STATUS = "status";
    public static final String LOCK_TOKEN = "lockToken";
    public static final String LOCKED_UNTIL = "lockedUntilEpochMillis";
    public static final String EXPIRES_AT = "expiresAtEpochSeconds";
    public static final String OUTCOME_TYPE = "outcomeType";
    public static final String ORDER_ID = "orderId";
    public static final String ORDER_STATUS = "orderStatus";
    public static final String ORDER_EXPIRES_AT = "orderExpiresAt";
    public static final String ERROR_CODE = "errorCode";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    private static final String SEPARATOR = "#";
    private static final int ID_PARTS = 2;

    /**
     * Builds the key of an item.
     *
     * @param customerId the owner of the key
     * @param key        the key chosen by the customer
     * @return {@code customerId#key}
     */
    public String idempotencyId(CustomerId customerId, IdempotencyKey key) {
        return customerId.value() + SEPARATOR + key.value();
    }

    /**
     * Attributes that store a definitive outcome.
     *
     * @param outcome the outcome of the request
     * @return the attribute names and values of the outcome
     */
    public Map<String, AttributeValue> outcomeAttributes(PlacementOutcome outcome) {
        return switch (outcome) {
            case PlacementOutcome.Accepted accepted -> Map.of(
                    OUTCOME_TYPE, AttributeValue.fromS(ACCEPTED),
                    ORDER_ID, AttributeValue.fromS(accepted.orderId().value().toString()),
                    ORDER_STATUS, AttributeValue.fromS(accepted.status().name()),
                    ORDER_EXPIRES_AT, AttributeValue.fromN(String.valueOf(accepted.expiresAt().toEpochMilli())));
            case PlacementOutcome.Rejected rejected -> Map.of(
                    OUTCOME_TYPE, AttributeValue.fromS(REJECTED),
                    ERROR_CODE, AttributeValue.fromS(rejected.error().getCode()));
        };
    }

    /**
     * Rebuilds the record of an item. An item that cannot be read is a data error.
     *
     * @param item the stored item
     * @return the record of the key
     */
    public IdempotencyRecord toDomain(Map<String, AttributeValue> item) {
        try {
            String[] parts = item.get(IDEMPOTENCY_ID).s().split(SEPARATOR, ID_PARTS);
            if (parts.length != ID_PARTS) {
                throw new IllegalArgumentException("Idempotency id must join the customer and the key");
            }
            return new IdempotencyRecord(new CustomerId(UUID.fromString(parts[0])),
                    new IdempotencyKey(UUID.fromString(parts[1])),
                    new RequestFingerprint(item.get(REQUEST_HASH).s()),
                    IdempotencyStatus.valueOf(item.get(STATUS).s()),
                    UUID.fromString(item.get(LOCK_TOKEN).s()),
                    Instant.ofEpochMilli(Long.parseLong(item.get(LOCKED_UNTIL).n())),
                    Optional.ofNullable(item.get(OUTCOME_TYPE)).map(type -> toOutcome(type.s(), item)).orElse(null));
        } catch (RuntimeException error) {
            throw DYNAMODB_ERROR.build(error);
        }
    }

    private static PlacementOutcome toOutcome(String type, Map<String, AttributeValue> item) {
        return ACCEPTED.equals(type)
                ? new PlacementOutcome.Accepted(new OrderId(UUID.fromString(item.get(ORDER_ID).s())),
                OrderStatus.valueOf(item.get(ORDER_STATUS).s()),
                Instant.ofEpochMilli(Long.parseLong(item.get(ORDER_EXPIRES_AT).n())))
                : new PlacementOutcome.Rejected(toError(item.get(ERROR_CODE).s()));
    }

    private static BusinessErrorMessage toError(String code) {
        return Arrays.stream(BusinessErrorMessage.values())
                .filter(error -> error.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown business error code " + code));
    }
}
