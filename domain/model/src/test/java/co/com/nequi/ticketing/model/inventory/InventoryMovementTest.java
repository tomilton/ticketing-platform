package co.com.nequi.ticketing.model.inventory;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryMovementTest {

    private static final Integer QUANTITY = 3;

    @ParameterizedTest
    @CsvSource({
            "NONE, 0, 0, 0, 0, false",
            "RESERVE, -3, 3, 0, 0, false",
            "SELL, 0, -3, 3, 0, false",
            "RELEASE, 3, -3, 0, 0, true",
            "GRANT, -3, 0, 0, 3, false"})
    void shouldDescribeCounterChangesWhenMovementIsApplied(InventoryMovement movement, Integer available,
                                                          Integer reserved, Integer sold, Integer complimentary,
                                                          Boolean releases) {
        // When / Then
        assertThat(movement.availableChange(QUANTITY)).isEqualTo(available);
        assertThat(movement.reservedChange(QUANTITY)).isEqualTo(reserved);
        assertThat(movement.soldChange(QUANTITY)).isEqualTo(sold);
        assertThat(movement.complimentaryChange(QUANTITY)).isEqualTo(complimentary);
        assertThat(movement.releasesCustomerTickets()).isEqualTo(releases);
        assertThat(available + reserved + sold + complimentary).isZero();
    }
}
