package co.com.nequi.ticketing.dynamodb.mapper;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.IdempotencyStatus;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.IdempotencyRecord;
import co.com.nequi.ticketing.model.idempotency.IdempotencyScope;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyEntityMapperTest {

    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final IdempotencyScope SCOPE = IdempotencyScope.ofCustomer(CUSTOMER_ID);
    private static final IdempotencyKey KEY = new IdempotencyKey(UUID.randomUUID());
    private static final RequestFingerprint FINGERPRINT = RequestFingerprint.of(EventId.newId(), new Quantity(2));
    private static final UUID LOCK_TOKEN = UUID.randomUUID();
    private static final Instant LOCKED_UNTIL = Instant.parse("2026-10-27T10:00:10.250Z");

    private final IdempotencyEntityMapper mapper = new IdempotencyEntityMapper();

    @Test
    void shouldJoinScopeAndKeyWhenIdIsBuilt() {
        // When / Then
        assertThat(mapper.idempotencyId(SCOPE, KEY)).isEqualTo(CUSTOMER_ID.value() + "#" + KEY.value());
    }

    @Test
    void shouldRebuildAdministratorScopeWhenIdHasSeveralSeparators() {
        // Given
        IdempotencyScope administrator = IdempotencyScope.ofAdministrator(CUSTOMER_ID);
        Map<String, AttributeValue> item = baseItem(IdempotencyStatus.IN_PROGRESS);
        item.put(IdempotencyEntityMapper.IDEMPOTENCY_ID, AttributeValue.fromS(mapper.idempotencyId(administrator, KEY)));

        // When / Then
        assertThat(mapper.toDomain(item)).isEqualTo(new IdempotencyRecord(administrator, KEY, FINGERPRINT,
                IdempotencyStatus.IN_PROGRESS, LOCK_TOKEN, LOCKED_UNTIL, null));
    }

    @Test
    void shouldRebuildRecordWhenKeyIsInProgress() {
        // Given
        Map<String, AttributeValue> item = baseItem(IdempotencyStatus.IN_PROGRESS);

        // When / Then
        assertThat(mapper.toDomain(item)).isEqualTo(new IdempotencyRecord(SCOPE, KEY, FINGERPRINT,
                IdempotencyStatus.IN_PROGRESS, LOCK_TOKEN, LOCKED_UNTIL, null));
    }

    @Test
    void shouldStoreAndRebuildAcceptedOutcomeWhenKeyIsCompleted() {
        // Given
        PlacementOutcome accepted = new PlacementOutcome.Accepted(OrderId.newId(), OrderStatus.RESERVED,
                Instant.parse("2026-10-27T10:10:00.123Z"));
        Map<String, AttributeValue> item = baseItem(IdempotencyStatus.COMPLETED);
        item.putAll(mapper.outcomeAttributes(accepted));

        // When / Then
        assertThat(item.get(IdempotencyEntityMapper.OUTCOME_TYPE)).isEqualTo(AttributeValue.fromS("ACCEPTED"));
        assertThat(mapper.toDomain(item).outcome()).isEqualTo(accepted);
    }

    @Test
    void shouldStoreAndRebuildRejectedOutcomeWhenKeyIsCompleted() {
        // Given
        PlacementOutcome rejected = new PlacementOutcome.Rejected(BusinessErrorMessage.TICKETS_SOLD_OUT);
        Map<String, AttributeValue> item = baseItem(IdempotencyStatus.COMPLETED);
        item.putAll(mapper.outcomeAttributes(rejected));

        // When / Then
        assertThat(mapper.outcomeAttributes(rejected)).isEqualTo(Map.of(
                IdempotencyEntityMapper.OUTCOME_TYPE, AttributeValue.fromS("REJECTED"),
                IdempotencyEntityMapper.ERROR_CODE, AttributeValue.fromS("TKB0003")));
        assertThat(mapper.toDomain(item).outcome()).isEqualTo(rejected);
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenIdHasNoSeparator() {
        // Given
        Map<String, AttributeValue> item = baseItem(IdempotencyStatus.IN_PROGRESS);
        item.put(IdempotencyEntityMapper.IDEMPOTENCY_ID, AttributeValue.fromS(CUSTOMER_ID.value().toString()));

        // When / Then
        assertDataError(item);
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenIdHasNoScope() {
        // Given
        Map<String, AttributeValue> item = baseItem(IdempotencyStatus.IN_PROGRESS);
        item.put(IdempotencyEntityMapper.IDEMPOTENCY_ID, AttributeValue.fromS("#" + KEY.value()));

        // When / Then
        assertDataError(item);
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenStoredErrorCodeIsUnknown() {
        // Given
        Map<String, AttributeValue> item = baseItem(IdempotencyStatus.COMPLETED);
        item.put(IdempotencyEntityMapper.OUTCOME_TYPE, AttributeValue.fromS("REJECTED"));
        item.put(IdempotencyEntityMapper.ERROR_CODE, AttributeValue.fromS("TKB9999"));

        // When / Then
        assertDataError(item);
    }

    private void assertDataError(Map<String, AttributeValue> item) {
        assertThatThrownBy(() -> mapper.toDomain(item))
                .isInstanceOf(TechnicalException.class)
                .extracting(error -> ((TechnicalException) error).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR);
    }

    private Map<String, AttributeValue> baseItem(IdempotencyStatus status) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(IdempotencyEntityMapper.IDEMPOTENCY_ID, AttributeValue.fromS(mapper.idempotencyId(SCOPE, KEY)));
        item.put(IdempotencyEntityMapper.REQUEST_HASH, AttributeValue.fromS(FINGERPRINT.value()));
        item.put(IdempotencyEntityMapper.STATUS, AttributeValue.fromS(status.name()));
        item.put(IdempotencyEntityMapper.LOCK_TOKEN, AttributeValue.fromS(LOCK_TOKEN.toString()));
        item.put(IdempotencyEntityMapper.LOCKED_UNTIL, AttributeValue.fromN(String.valueOf(LOCKED_UNTIL.toEpochMilli())));
        return item;
    }
}
