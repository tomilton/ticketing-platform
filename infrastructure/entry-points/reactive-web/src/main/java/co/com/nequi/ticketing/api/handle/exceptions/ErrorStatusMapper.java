package co.com.nequi.ticketing.api.handle.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.EMPTY_REQUEST_BODY;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.INVALID_JSON;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.UNEXPECTED_ERROR;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.VALIDATION_ERROR;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_DATE_NOT_IN_FUTURE;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_FOUND;

/**
 * HTTP status of each catalog code. An unknown code is answered as 500.
 */
@Component
public class ErrorStatusMapper {

    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.of(
            EVENT_NOT_FOUND.getCode(), HttpStatus.NOT_FOUND,
            EVENT_DATE_NOT_IN_FUTURE.getCode(), HttpStatus.UNPROCESSABLE_CONTENT,
            VALIDATION_ERROR.getCode(), HttpStatus.BAD_REQUEST,
            INVALID_JSON.getCode(), HttpStatus.BAD_REQUEST,
            EMPTY_REQUEST_BODY.getCode(), HttpStatus.BAD_REQUEST,
            DYNAMODB_ERROR.getCode(), HttpStatus.INTERNAL_SERVER_ERROR,
            UNEXPECTED_ERROR.getCode(), HttpStatus.INTERNAL_SERVER_ERROR);

    public HttpStatus getStatusFor(String code) {
        return STATUS_BY_CODE.getOrDefault(code, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
