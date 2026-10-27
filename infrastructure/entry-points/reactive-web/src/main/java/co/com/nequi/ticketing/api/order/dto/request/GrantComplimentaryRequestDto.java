package co.com.nequi.ticketing.api.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of {@code POST /events/{eventId}/complimentary}. The configured limit per grant, which can be
 * lower, is applied by the use case.
 *
 * @param customerId the customer who receives the tickets
 * @param quantity   the number of complimentary tickets
 */
@Schema(name = "GrantComplimentaryRequest")
public record GrantComplimentaryRequestDto(@NotNull UUID customerId,
                                           @NotNull @Min(GrantComplimentaryRequestDto.MIN_QUANTITY)
                                           @Max(GrantComplimentaryRequestDto.MAX_QUANTITY) Integer quantity) {

    static final long MIN_QUANTITY = 1;
    static final long MAX_QUANTITY = 50;
}
