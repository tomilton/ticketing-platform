package co.com.nequi.ticketing.dynamodb.mapper;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.entity.OrderEntity;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderEntityMapperTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-27T10:00:00.123Z");

    private final OrderEntityMapper mapper = Mappers.getMapper(OrderEntityMapper.class);

    @Test
    void shouldStoreIdentifiersAsTextAndInstantsInMillisecondsWhenOrderIsMapped() {
        // Given
        Order order = order();

        // When
        OrderEntity entity = mapper.toEntity(order);

        // Then
        assertThat(entity).usingRecursiveComparison().isEqualTo(entity(order));
    }

    @Test
    void shouldRebuildOrderWhenEntityIsMapped() {
        // Given
        Order order = order();

        // When
        Order rebuilt = mapper.toDomain(entity(order));

        // Then
        assertThat(rebuilt).usingRecursiveComparison().isEqualTo(order);
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenItemBreaksDomainRule() {
        // Given
        OrderEntity entity = entity(order());
        entity.setQuantity(3);

        // When / Then
        assertThatThrownBy(() -> mapper.toDomain(entity))
                .isInstanceOf(TechnicalException.class)
                .extracting(error -> ((TechnicalException) error).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR);
    }

    private static Order order() {
        return Order.place(OrderId.newId(), new CustomerId(UUID.randomUUID()), EventId.newId(), new Quantity(2), 1,
                CREATED_AT, Duration.ofMinutes(10));
    }

    private static OrderEntity entity(Order order) {
        OrderEntity entity = new OrderEntity();
        entity.setOrderId(order.getOrderId().value().toString());
        entity.setCustomerId(order.getCustomerId().value().toString());
        entity.setEventId(order.getEventId().value().toString());
        entity.setQuantity(2);
        entity.setTickets(order.getTickets().stream().map(ticket -> ticket.value().toString()).toList());
        entity.setStatus(OrderStatus.RESERVED);
        entity.setCreatedAt(CREATED_AT.toEpochMilli());
        entity.setExpiresAt(CREATED_AT.plus(Duration.ofMinutes(10)).toEpochMilli());
        entity.setShardId(1);
        entity.setVersion(1L);
        return entity;
    }
}
