package co.com.nequi.ticketing.dynamodb.mapper;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.entity.InventoryShardEntity;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryShardEntityMapperTest {

    private static final UUID EVENT_ID = UUID.fromString("3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f");
    private static final InventoryShard SHARD = new InventoryShard(new EventId(EVENT_ID), 1, 10, 4, 3, 2, 1, 5L);

    private final InventoryShardEntityMapper mapper = Mappers.getMapper(InventoryShardEntityMapper.class);

    @Test
    void shouldCopyCountersWhenShardIsMapped() {
        // When
        InventoryShardEntity entity = mapper.toEntity(SHARD);

        // Then
        assertThat(entity).usingRecursiveComparison().isEqualTo(entity(4));
    }

    @Test
    void shouldRebuildShardWhenEntityIsMapped() {
        // When / Then
        assertThat(mapper.toDomain(entity(4))).isEqualTo(SHARD);
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenCountersDoNotAddUpToCapacity() {
        // Given
        InventoryShardEntity invalid = entity(9);

        // When / Then
        assertThatThrownBy(() -> mapper.toDomain(invalid))
                .isInstanceOf(TechnicalException.class)
                .extracting(error -> ((TechnicalException) error).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR);
    }

    private static InventoryShardEntity entity(Integer available) {
        InventoryShardEntity entity = new InventoryShardEntity();
        entity.setEventId(EVENT_ID.toString());
        entity.setShardId(1);
        entity.setCapacity(10);
        entity.setAvailable(available);
        entity.setReserved(3);
        entity.setSold(2);
        entity.setComplimentary(1);
        entity.setVersion(5L);
        return entity;
    }
}
