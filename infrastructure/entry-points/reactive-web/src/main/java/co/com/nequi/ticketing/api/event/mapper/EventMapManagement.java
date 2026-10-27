package co.com.nequi.ticketing.api.event.mapper;

import co.com.nequi.ticketing.api.commons.Meta;
import co.com.nequi.ticketing.api.commons.Response;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import org.springframework.stereotype.Component;

/**
 * Wraps the event payloads in the success envelope.
 */
@Component
public class EventMapManagement {

    public <T> Response<T> buildResponse(T data, SuccessMessage successMessage) {
        return new Response<>(Meta.getSuccessMeta(successMessage), data);
    }
}
