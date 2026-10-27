package co.com.nequi.ticketing.model.enums;

/**
 * Who caused a change of status of an order: the customer when the order is placed, the worker
 * when it is processed and the administrator when complimentary tickets are granted.
 */
public enum ChangeActor {
    CUSTOMER,
    WORKER,
    ADMIN
}
