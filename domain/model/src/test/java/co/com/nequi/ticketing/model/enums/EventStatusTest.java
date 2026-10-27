package co.com.nequi.ticketing.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class EventStatusTest {

    @ParameterizedTest
    @CsvSource({"ACTIVE, true, false", "CANCELLED, false, true", "FINISHED, false, true"})
    void shouldReportSaleAndFinalStateWhenStatusIsEvaluated(EventStatus status, boolean onSale, boolean isFinal) {
        // When / Then
        assertThat(status.isOnSale()).isEqualTo(onSale);
        assertThat(status.isFinal()).isEqualTo(isFinal);
    }

    @ParameterizedTest
    @CsvSource({
            "ACTIVE, CANCELLED, true", "ACTIVE, FINISHED, true", "ACTIVE, ACTIVE, false",
            "CANCELLED, ACTIVE, false", "CANCELLED, FINISHED, false",
            "FINISHED, ACTIVE, false", "FINISHED, CANCELLED, false"
    })
    void shouldAllowOnlyTransitionsFromActiveWhenTargetIsFinal(EventStatus source, EventStatus target,
                                                               boolean allowed) {
        // When / Then
        assertThat(source.canTransitionTo(target)).isEqualTo(allowed);
    }

    @Test
    void shouldListAllStatusesWhenValidValuesIsCalled() {
        // When / Then
        assertThat(EventStatus.validValues()).isEqualTo("ACTIVE, CANCELLED, FINISHED");
    }
}
