package co.com.nequi.ticketing.api.handle.exceptions;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorStatusMapperTest {

    private final ErrorStatusMapper mapper = new ErrorStatusMapper();

    @ParameterizedTest
    @CsvSource({
            "TKB0001, NOT_FOUND",
            "TKB0010, UNPROCESSABLE_CONTENT",
            "TKT0001, BAD_REQUEST",
            "TKT0002, BAD_REQUEST",
            "TKT0003, BAD_REQUEST",
            "TKT0005, INTERNAL_SERVER_ERROR",
            "TKT0009, INTERNAL_SERVER_ERROR",
            "TKX9999, INTERNAL_SERVER_ERROR"
    })
    void shouldMapCatalogCodeToHttpStatusWhenCodeIsGiven(String code, HttpStatus expected) {
        // When / Then
        assertThat(mapper.getStatusFor(code)).isEqualTo(expected);
    }
}
