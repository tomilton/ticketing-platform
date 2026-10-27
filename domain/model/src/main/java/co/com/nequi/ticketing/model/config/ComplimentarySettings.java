package co.com.nequi.ticketing.model.config;

import co.com.nequi.ticketing.model.order.ComplimentaryQuantity;

import java.util.Objects;

/**
 * Business parameters of the complimentary tickets.
 *
 * @param maxPerGrant the tickets of one grant, between {@value ComplimentaryQuantity#MIN} and
 *                    {@value ComplimentaryQuantity#MAX_PER_GRANT_LIMIT}
 */
public record ComplimentarySettings(Integer maxPerGrant) {

    public ComplimentarySettings {
        Objects.requireNonNull(maxPerGrant, "Max complimentary tickets per grant must not be null");
        if (maxPerGrant < ComplimentaryQuantity.MIN || maxPerGrant > ComplimentaryQuantity.MAX_PER_GRANT_LIMIT) {
            throw new IllegalArgumentException("Max complimentary tickets per grant must be between "
                    + ComplimentaryQuantity.MIN + " and " + ComplimentaryQuantity.MAX_PER_GRANT_LIMIT);
        }
    }
}
