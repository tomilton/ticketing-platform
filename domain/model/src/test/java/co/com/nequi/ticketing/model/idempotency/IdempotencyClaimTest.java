package co.com.nequi.ticketing.model.idempotency;

import co.com.nequi.ticketing.model.enums.IdempotencyStatus;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyClaimTest {

    @Test
    void shouldKeepTokenOrRecordWhenClaimIsCreated() {
        // Given
        UUID token = UUID.randomUUID();
        IdempotencyRecord stored = new IdempotencyRecord(new CustomerId(UUID.randomUUID()),
                new IdempotencyKey(UUID.randomUUID()), RequestFingerprint.of(EventId.newId(), new Quantity(1)),
                IdempotencyStatus.IN_PROGRESS, token, Instant.parse("2026-10-27T10:00:00Z"), null);

        // When
        IdempotencyClaim granted = new IdempotencyClaim.Granted(token);
        IdempotencyClaim existing = new IdempotencyClaim.Existing(stored);

        // Then
        assertThat(granted).isInstanceOfSatisfying(IdempotencyClaim.Granted.class,
                claim -> assertThat(claim.lockToken()).isEqualTo(token));
        assertThat(existing).isInstanceOfSatisfying(IdempotencyClaim.Existing.class,
                claim -> assertThat(claim.record()).isEqualTo(stored));
    }

    @Test
    void shouldThrowNullPointerExceptionWhenComponentIsNull() {
        // When / Then
        assertThatThrownBy(() -> new IdempotencyClaim.Granted(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyClaim.Existing(null)).isInstanceOf(NullPointerException.class);
    }
}
