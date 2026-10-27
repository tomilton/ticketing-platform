package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.OrderStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Definitive result of a purchase request, kept with its idempotency key so that a repetition
 * returns exactly the same result.
 */
public sealed interface PlacementOutcome {

    /**
     * The tickets were reserved and the order was accepted.
     *
     * @param orderId   the new order
     * @param status    the status of the order when it was accepted
     * @param expiresAt the end of the reservation
     */
    record Accepted(OrderId orderId, OrderStatus status, Instant expiresAt) implements PlacementOutcome {

        public Accepted {
            Objects.requireNonNull(orderId, "Order id must not be null");
            Objects.requireNonNull(status, "Status must not be null");
            Objects.requireNonNull(expiresAt, "Expiration instant must not be null");
        }

        /**
         * Result of a reserved order.
         *
         * @param order the reserved order
         * @return the accepted outcome of the order
         */
        public static Accepted of(Order order) {
            return new Accepted(order.getOrderId(), order.getStatus(), order.getExpiresAt());
        }
    }

    /**
     * The request was rejected by a business rule.
     *
     * @param error the business error of the rejection
     */
    record Rejected(BusinessErrorMessage error) implements PlacementOutcome {

        public Rejected {
            Objects.requireNonNull(error, "Error must not be null");
        }
    }
}
