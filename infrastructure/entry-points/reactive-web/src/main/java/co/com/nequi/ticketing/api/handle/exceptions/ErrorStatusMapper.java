package co.com.nequi.ticketing.api.handle.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.EMPTY_REQUEST_BODY;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.FORBIDDEN;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.INVALID_JSON;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.INVALID_WEBHOOK_SIGNATURE;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.MISSING_IDEMPOTENCY_KEY;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.PAYLOAD_TOO_LARGE;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.RATE_LIMITED;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.SQS_ERROR;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.UNAUTHORIZED;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.UNEXPECTED_ERROR;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.VALIDATION_ERROR;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_DATE_NOT_IN_FUTURE;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_ACTIVE;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_FOUND;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.IDEMPOTENCY_KEY_REUSED;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.INVALID_STATE_TRANSITION;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.ORDER_NOT_FOUND;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.REQUEST_IN_PROGRESS;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.TICKETS_SOLD_OUT;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.TICKET_LIMIT_EXCEEDED;

/**
 * HTTP status of each catalog code. An unknown code is answered as 500.
 */
@Component
public class ErrorStatusMapper {

    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.ofEntries(
            Map.entry(EVENT_NOT_FOUND.getCode(), HttpStatus.NOT_FOUND),
            Map.entry(ORDER_NOT_FOUND.getCode(), HttpStatus.NOT_FOUND),
            Map.entry(TICKETS_SOLD_OUT.getCode(), HttpStatus.CONFLICT),
            Map.entry(TICKET_LIMIT_EXCEEDED.getCode(), HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry(IDEMPOTENCY_KEY_REUSED.getCode(), HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry(REQUEST_IN_PROGRESS.getCode(), HttpStatus.CONFLICT),
            Map.entry(EVENT_NOT_ACTIVE.getCode(), HttpStatus.CONFLICT),
            Map.entry(INVALID_STATE_TRANSITION.getCode(), HttpStatus.CONFLICT),
            Map.entry(EVENT_DATE_NOT_IN_FUTURE.getCode(), HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry(VALIDATION_ERROR.getCode(), HttpStatus.BAD_REQUEST),
            Map.entry(INVALID_JSON.getCode(), HttpStatus.BAD_REQUEST),
            Map.entry(EMPTY_REQUEST_BODY.getCode(), HttpStatus.BAD_REQUEST),
            Map.entry(MISSING_IDEMPOTENCY_KEY.getCode(), HttpStatus.BAD_REQUEST),
            Map.entry(UNAUTHORIZED.getCode(), HttpStatus.UNAUTHORIZED),
            Map.entry(FORBIDDEN.getCode(), HttpStatus.FORBIDDEN),
            Map.entry(RATE_LIMITED.getCode(), HttpStatus.TOO_MANY_REQUESTS),
            Map.entry(INVALID_WEBHOOK_SIGNATURE.getCode(), HttpStatus.UNAUTHORIZED),
            Map.entry(PAYLOAD_TOO_LARGE.getCode(), HttpStatus.CONTENT_TOO_LARGE),
            Map.entry(DYNAMODB_ERROR.getCode(), HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(SQS_ERROR.getCode(), HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(PAYMENT_GATEWAY_ERROR.getCode(), HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(UNEXPECTED_ERROR.getCode(), HttpStatus.INTERNAL_SERVER_ERROR));

    public HttpStatus getStatusFor(String code) {
        return STATUS_BY_CODE.getOrDefault(code, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
