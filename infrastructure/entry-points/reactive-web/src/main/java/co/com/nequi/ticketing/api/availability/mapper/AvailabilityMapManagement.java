package co.com.nequi.ticketing.api.availability.mapper;

import co.com.nequi.ticketing.api.commons.Meta;
import co.com.nequi.ticketing.api.commons.Response;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import org.springframework.stereotype.Component;

/**
 * Wraps the availability payloads in the success envelope.
 */
@Component
public class AvailabilityMapManagement {

    public <T> Response<T> buildResponse(T data, SuccessMessage successMessage) {
        return new Response<>(Meta.getSuccessMeta(successMessage), data);
    }
}
