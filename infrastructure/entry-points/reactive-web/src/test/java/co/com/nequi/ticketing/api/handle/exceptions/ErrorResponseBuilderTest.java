package co.com.nequi.ticketing.api.handle.exceptions;

import co.com.nequi.ticketing.api.commons.ErrorResponse;
import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import reactor.test.StepVerifier;

import java.net.URI;

class ErrorResponseBuilderTest {

    private final ErrorResponseBuilder builder = new ErrorResponseBuilder(new ErrorStatusMapper());

    @Test
    void shouldAnswerNotFoundWithValidationCodeWhenRouteDoesNotExist() {
        // When / Then
        StepVerifier.create(builder.buildErrorResponse(new NoResourceFoundException(URI.create("/unknown"), "unknown")))
                .expectNext(new ErrorResult(new ErrorResponse("Resource not found", "TKT0001",
                        "Resource not found", null), HttpStatus.NOT_FOUND))
                .verifyComplete();
    }

    @Test
    void shouldUseCatalogEntryWhenErrorIsTechnical() {
        // When / Then
        StepVerifier.create(builder.buildErrorResponse(TechnicalErrorMessage.DYNAMODB_ERROR.build(new IllegalStateException())))
                .expectNext(new ErrorResult(new ErrorResponse("Database error", "TKT0005", "Database error", null),
                        HttpStatus.INTERNAL_SERVER_ERROR))
                .verifyComplete();
    }

    @Test
    void shouldNameFieldWhenErrorIsValidation() {
        // When / Then
        StepVerifier.create(builder.buildErrorResponse(new ValidationException("limit", "must be positive")))
                .expectNext(new ErrorResult(new ErrorResponse("Error while validating data", "TKT0001",
                        "limit: must be positive", null), HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    @Test
    void shouldUseCatalogEntryWhenErrorIsBusiness() {
        // When / Then
        StepVerifier.create(builder.buildErrorResponse(BusinessErrorMessage.EVENT_DATE_NOT_IN_FUTURE.build()))
                .expectNext(new ErrorResult(new ErrorResponse("Event date must be in the future", "TKB0010",
                        "Event date must be in the future", null), HttpStatus.UNPROCESSABLE_CONTENT))
                .verifyComplete();
    }

    @Test
    void shouldAnswerUnexpectedErrorWhenErrorIsUnknown() {
        // When / Then
        StepVerifier.create(builder.buildErrorResponse(new IllegalStateException("boom")))
                .expectNext(new ErrorResult(new ErrorResponse("Unexpected error", "TKT0009", "Unexpected error", null),
                        HttpStatus.INTERNAL_SERVER_ERROR))
                .verifyComplete();
    }
}
