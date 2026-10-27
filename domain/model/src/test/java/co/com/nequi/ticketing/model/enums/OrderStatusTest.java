package co.com.nequi.ticketing.model.enums;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    private static final Set<String> ALLOWED = Set.of(
            "RESERVED>PENDING_CONFIRMATION", "RESERVED>EXPIRED", "RESERVED>CANCELLED",
            "PENDING_CONFIRMATION>SOLD", "PENDING_CONFIRMATION>CANCELLED", "PENDING_CONFIRMATION>EXPIRED");

    @ParameterizedTest
    @CsvSource({
            "RESERVED, true, false, true",
            "PENDING_CONFIRMATION, true, false, true",
            "SOLD, false, true, true",
            "CANCELLED, false, true, false",
            "EXPIRED, false, true, false",
            "COMPLIMENTARY, false, true, false"})
    void shouldDescribeStatusWhenItIsQueried(OrderStatus status, Boolean active, Boolean terminal, Boolean holds) {
        // When / Then
        assertThat(status.isActive()).isEqualTo(active);
        assertThat(status.isFinal()).isEqualTo(terminal);
        assertThat(status.holdsTickets()).isEqualTo(holds);
    }

    @ParameterizedTest
    @MethodSource("allCombinations")
    void shouldAllowOnlyStateMachineTransitionsWhenCanTransitionToIsCalled(OrderStatus from, OrderStatus to) {
        // When / Then
        assertThat(from.canTransitionTo(to)).isEqualTo(ALLOWED.contains(from + ">" + to));
    }

    private static Stream<Arguments> allCombinations() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(OrderStatus.values()).map(to -> Arguments.of(from, to)));
    }
}
