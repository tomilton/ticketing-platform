package co.com.nequi.ticketing.model.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VenueTest {

    private static final String NAME = "Movistar Arena";
    private static final String CITY = "Bogota";

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void shouldThrowIllegalArgumentExceptionWhenNameOrCityIsBlank(String blank) {
        // When / Then
        assertThatThrownBy(() -> new Venue(blank, CITY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Venue(NAME, blank)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenNameOrCityIsNull() {
        // When / Then
        assertThatThrownBy(() -> new Venue(null, CITY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Venue(NAME, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenTextHas101Characters() {
        // Given
        String tooLong = "a".repeat(Venue.TEXT_MAX_LENGTH + 1);

        // When / Then
        assertThatThrownBy(() -> new Venue(tooLong, CITY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Venue(NAME, tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCreateVenueWhenTextHas100Characters() {
        // Given
        String maxLength = "a".repeat(Venue.TEXT_MAX_LENGTH);

        // When
        Venue venue = new Venue(maxLength, maxLength);

        // Then
        assertThat(venue).isEqualTo(new Venue(maxLength, maxLength));
    }
}
