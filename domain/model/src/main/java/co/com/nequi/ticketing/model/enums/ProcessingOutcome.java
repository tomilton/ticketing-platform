package co.com.nequi.ticketing.model.enums;

/**
 * Result of processing an accepted order, used by the worker for its metrics.
 */
public enum ProcessingOutcome {
    /** The payment was approved and the tickets were sold. */
    SOLD,
    /** The payment was declined or the event stopped being on sale; the tickets were released. */
    CANCELLED,
    /** The reservation ended before the sale; the tickets were released. */
    EXPIRED,
    /** The order was already final or another copy of the message won the race. */
    IGNORED,
    /** The notice of the payment arrived before the payment was requested; it is applied in a later delivery. */
    DEFERRED
}
