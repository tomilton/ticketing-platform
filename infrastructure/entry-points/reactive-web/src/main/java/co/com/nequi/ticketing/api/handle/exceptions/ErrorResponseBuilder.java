package co.com.nequi.ticketing.api.handle.exceptions;

import co.com.nequi.ticketing.api.commons.ErrorResponse;
import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import reactor.core.publisher.Mono;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.UNEXPECTED_ERROR;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.VALIDATION_ERROR;

/**
 * Builds the error of each kind of exception. Business and client errors are logged in WARN and
 * server errors in ERROR with their cause; no stack trace reaches the client.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorResponseBuilder {

    private static final String RESOURCE_NOT_FOUND = "Resource not found";

    private final ErrorStatusMapper statusMapper;

    public Mono<ErrorResult> buildErrorResponse(NoResourceFoundException error) {
        log.warn("Route not found, code={}", VALIDATION_ERROR.getCode());
        return Mono.just(new ErrorResult(
                new ErrorResponse(RESOURCE_NOT_FOUND, VALIDATION_ERROR.getCode(), RESOURCE_NOT_FOUND, null),
                HttpStatus.NOT_FOUND));
    }

    public Mono<ErrorResult> buildErrorResponse(TechnicalException error) {
        TechnicalErrorMessage errorMessage = error.getErrorMessage();
        return Mono.just(result(errorMessage.getMessage(), errorMessage.getCode(), errorMessage.getMessage(), error));
    }

    public Mono<ErrorResult> buildErrorResponse(ValidationException error) {
        return Mono.just(result(VALIDATION_ERROR.getMessage(), VALIDATION_ERROR.getCode(), error.getMessage(), error));
    }

    public Mono<ErrorResult> buildErrorResponse(BusinessException error) {
        BusinessErrorMessage errorMessage = error.getBusinessErrorMessage();
        return Mono.just(result(errorMessage.getMessage(), errorMessage.getCode(), errorMessage.getMessage(), error));
    }

    public Mono<ErrorResult> buildErrorResponse(Throwable error) {
        return Mono.just(result(UNEXPECTED_ERROR.getMessage(), UNEXPECTED_ERROR.getCode(),
                UNEXPECTED_ERROR.getMessage(), error));
    }

    private ErrorResult result(String reason, String code, String message, Throwable error) {
        HttpStatus status = statusMapper.getStatusFor(code);
        if (status.is5xxServerError()) {
            log.error("Request failed, code={}", code, error);
        } else {
            log.warn("Request rejected, code={}, detail={}", code, message);
        }
        return new ErrorResult(new ErrorResponse(reason, code, message, null), status);
    }
}
