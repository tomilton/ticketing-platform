package co.com.nequi.ticketing.dynamodb.transaction;

import co.com.nequi.ticketing.dynamodb.support.OrderFixtures;
import co.com.nequi.ticketing.model.enums.ChangeActor;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.OrderStatusChange;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditItemBuilderTest {

    private static final Instant AT = Instant.parse("2026-10-27T10:00:00Z");
    private static final String ADMINISTRATOR = "22222222-2222-4222-8222-222222222222";

    private final AuditItemBuilder builder = new AuditItemBuilder(OrderFixtures.properties(10, Duration.ofSeconds(5)));

    @Test
    void shouldWriteActorIdWhenChangeHasOne() {
        // Given
        OrderStatusChange change = new OrderStatusChange(OrderId.newId(), null, OrderStatus.COMPLIMENTARY,
                StatusChangeReason.COMPLIMENTARY_GRANTED, ChangeActor.ADMIN, AT, ADMINISTRATOR);

        // When / Then
        assertThat(builder.put(change).put().item())
                .containsEntry("actor", AttributeValue.fromS("ADMIN"))
                .containsEntry("actorId", AttributeValue.fromS(ADMINISTRATOR));
    }

    @Test
    void shouldOmitActorIdWhenChangeHasNone() {
        // Given
        OrderStatusChange change = new OrderStatusChange(OrderId.newId(), null, OrderStatus.RESERVED,
                StatusChangeReason.PLACED, ChangeActor.CUSTOMER, AT);

        // When / Then
        assertThat(builder.put(change).put().item()).doesNotContainKey("actorId");
    }
}
