package co.com.nequi.ticketing.api.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of {@code POST /orders}. The configured limit per order, which can be lower, is applied by
 * the use case.
 *
 * @param eventId  the event
 * @param quantity the number of tickets
 */
@Schema(name = "PlaceOrderRequest")
public record PlaceOrderRequestDto(@NotNull UUID eventId,
                                   @NotNull @Min(PlaceOrderRequestDto.MIN_QUANTITY)
                                   @Max(PlaceOrderRequestDto.MAX_QUANTITY) Integer quantity) {

    static final long MIN_QUANTITY = 1;
    static final long MAX_QUANTITY = 6;
}
