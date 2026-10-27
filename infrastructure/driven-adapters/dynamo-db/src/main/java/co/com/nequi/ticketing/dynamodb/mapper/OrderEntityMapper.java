package co.com.nequi.ticketing.dynamodb.mapper;

import co.com.nequi.ticketing.dynamodb.entity.OrderEntity;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.TicketId;
import co.com.nequi.ticketing.model.shared.CustomerId;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;

/**
 * Maps {@link Order} to {@link OrderEntity} and back. The index buckets and the publication instant
 * belong to the persistence and are written by the transactions, not by this mapper.
 */
@Mapper(componentModel = "spring")
public interface OrderEntityMapper extends DomainValueConversions {

    @Mapping(target = "activeBucket", ignore = true)
    @Mapping(target = "publishBucket", ignore = true)
    @Mapping(target = "publishedAt", ignore = true)
    OrderEntity toEntity(Order order);

    Order buildOrder(OrderEntity entity);

    /**
     * Rebuilds the order of an item. An item that breaks a domain rule is a data error.
     *
     * @param entity the stored item
     * @return the order
     */
    default Order toDomain(OrderEntity entity) {
        try {
            return buildOrder(entity);
        } catch (RuntimeException error) {
            throw DYNAMODB_ERROR.build(error);
        }
    }

    default String toText(OrderId orderId) {
        return orderId.value().toString();
    }

    default OrderId toOrderId(String orderId) {
        return new OrderId(UUID.fromString(orderId));
    }

    default String toText(CustomerId customerId) {
        return customerId.value().toString();
    }

    default CustomerId toCustomerId(String customerId) {
        return new CustomerId(UUID.fromString(customerId));
    }

    default String toText(TicketId ticketId) {
        return ticketId.value().toString();
    }

    default TicketId toTicketId(String ticketId) {
        return new TicketId(UUID.fromString(ticketId));
    }
}
