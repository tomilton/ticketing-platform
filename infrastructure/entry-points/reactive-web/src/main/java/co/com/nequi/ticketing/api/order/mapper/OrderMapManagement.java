package co.com.nequi.ticketing.api.order.mapper;

import co.com.nequi.ticketing.api.commons.Meta;
import co.com.nequi.ticketing.api.commons.Response;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import org.springframework.stereotype.Component;

/**
 * Wraps the order payloads in the success envelope.
 */
@Component
public class OrderMapManagement {

    public <T> Response<T> buildResponse(T data, SuccessMessage successMessage) {
        return new Response<>(Meta.getSuccessMeta(successMessage), data);
    }
}
