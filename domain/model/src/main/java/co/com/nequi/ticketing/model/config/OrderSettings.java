package co.com.nequi.ticketing.model.config;

import co.com.nequi.ticketing.model.order.Quantity;

import java.time.Duration;
import java.util.Objects;

/**
 * Business parameters of the orders.
 *
 * @param maxTicketsPerOrder    the tickets of one order, between {@value Quantity#MIN} and
 *                              {@value Quantity#MAX_PER_ORDER_LIMIT}
 * @param maxTicketsPerCustomer the tickets a customer can hold for one event, at least {@code maxTicketsPerOrder}
 * @param reservationTtl        how long a reservation holds its tickets; it must be positive
 * @param republishGrace        how old an unpublished order must be to publish it again; it must be positive
 * @param republishConcurrency  the orders published again at the same time, at least {@value #MIN_CONCURRENCY}
 */
public record OrderSettings(Integer maxTicketsPerOrder, Integer maxTicketsPerCustomer, Duration reservationTtl,
                            Duration republishGrace, Integer republishConcurrency) {

    public static final int MIN_CONCURRENCY = 1;

    public OrderSettings {
        Objects.requireNonNull(maxTicketsPerOrder, "Max tickets per order must not be null");
        Objects.requireNonNull(maxTicketsPerCustomer, "Max tickets per customer must not be null");
        Objects.requireNonNull(republishConcurrency, "Republish concurrency must not be null");
        if (maxTicketsPerOrder < Quantity.MIN || maxTicketsPerOrder > Quantity.MAX_PER_ORDER_LIMIT) {
            throw new IllegalArgumentException("Max tickets per order must be between " + Quantity.MIN + " and "
                    + Quantity.MAX_PER_ORDER_LIMIT);
        }
        if (maxTicketsPerCustomer < maxTicketsPerOrder) {
            throw new IllegalArgumentException("Max tickets per customer must not be lower than per order");
        }
        if (republishConcurrency < MIN_CONCURRENCY) {
            throw new IllegalArgumentException("Republish concurrency must be at least " + MIN_CONCURRENCY);
        }
        DurationRules.requirePositive(reservationTtl, "Reservation TTL");
        DurationRules.requirePositive(republishGrace, "Republish grace");
    }
}
