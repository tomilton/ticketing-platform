package co.com.nequi.ticketing.model.enums;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TicketStatusTest {

    @ParameterizedTest
    @CsvSource({
            "RESERVED, RESERVED",
            "PENDING_CONFIRMATION, PENDING_CONFIRMATION",
            "SOLD, SOLD",
            "CANCELLED, AVAILABLE",
            "EXPIRED, AVAILABLE"})
    void shouldDeriveTicketStatusWhenOrderHasEffectiveStatus(OrderStatus effective, TicketStatus expected) {
        // When / Then
        assertThat(TicketStatus.of(effective)).isEqualTo(expected);
    }
}
