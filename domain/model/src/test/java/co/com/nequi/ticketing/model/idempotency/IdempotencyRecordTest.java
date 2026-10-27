package co.com.nequi.ticketing.model.idempotency;

import co.com.nequi.ticketing.model.enums.IdempotencyStatus;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.UUID;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.TICKETS_SOLD_OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyRecordTest {

    private static final Instant LOCKED_UNTIL = Instant.parse("2026-10-27T10:00:10Z");
    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final IdempotencyKey KEY = new IdempotencyKey(UUID.randomUUID());
    private static final EventId EVENT_ID = EventId.newId();
    private static final RequestFingerprint FINGERPRINT = RequestFingerprint.of(EVENT_ID, new Quantity(2));

    @Test
    void shouldMatchWhenFingerprintIsTheSame() {
        // Given
        IdempotencyRecord inProgress = inProgress();

        // When / Then
        assertThat(inProgress.matches(RequestFingerprint.of(EVENT_ID, new Quantity(2)))).isTrue();
        assertThat(inProgress.matches(RequestFingerprint.of(EVENT_ID, new Quantity(3)))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"-1, true", "0, false", "1, false"})
    void shouldTellLeaseWhenKeyIsInProgress(Long millisFromLockEnd, Boolean expected) {
        // When / Then
        assertThat(inProgress().isLeaseActive(LOCKED_UNTIL.plusMillis(millisFromLockEnd))).isEqualTo(expected);
    }

    @Test
    void shouldReturnInactiveLeaseWhenKeyIsCompleted() {
        // Given
        IdempotencyRecord completed = new IdempotencyRecord(CUSTOMER_ID, KEY, FINGERPRINT,
                IdempotencyStatus.COMPLETED, UUID.randomUUID(), LOCKED_UNTIL, new PlacementOutcome.Rejected(TICKETS_SOLD_OUT));

        // When / Then
        assertThat(completed.isLeaseActive(LOCKED_UNTIL.minusSeconds(1))).isFalse();
    }

    @Test
    void shouldThrowNullPointerExceptionWhenRequiredComponentIsNull() {
        // Given
        UUID token = UUID.randomUUID();
        IdempotencyStatus status = IdempotencyStatus.IN_PROGRESS;

        // When / Then
        assertThatThrownBy(() -> new IdempotencyRecord(null, KEY, FINGERPRINT, status, token, LOCKED_UNTIL, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyRecord(CUSTOMER_ID, null, FINGERPRINT, status, token, LOCKED_UNTIL,
                null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyRecord(CUSTOMER_ID, KEY, null, status, token, LOCKED_UNTIL, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyRecord(CUSTOMER_ID, KEY, FINGERPRINT, null, token, LOCKED_UNTIL,
                null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyRecord(CUSTOMER_ID, KEY, FINGERPRINT, status, null, LOCKED_UNTIL,
                null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyRecord(CUSTOMER_ID, KEY, FINGERPRINT, status, token, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static IdempotencyRecord inProgress() {
        return new IdempotencyRecord(CUSTOMER_ID, KEY, FINGERPRINT, IdempotencyStatus.IN_PROGRESS,
                UUID.randomUUID(), LOCKED_UNTIL, null);
    }
}
