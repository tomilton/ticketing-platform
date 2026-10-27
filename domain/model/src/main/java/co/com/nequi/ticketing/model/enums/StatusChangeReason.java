package co.com.nequi.ticketing.model.enums;

/**
 * Reason of a change of status of an order, kept in its audit trail.
 */
public enum StatusChangeReason {
    PLACED,
    PAYMENT_REQUESTED,
    PAYMENT_APPROVED,
    PAYMENT_DECLINED,
    EVENT_NOT_ACTIVE,
    RESERVATION_EXPIRED,
    PAYMENT_AFTER_EXPIRY,
    COMPLIMENTARY_GRANTED
}
