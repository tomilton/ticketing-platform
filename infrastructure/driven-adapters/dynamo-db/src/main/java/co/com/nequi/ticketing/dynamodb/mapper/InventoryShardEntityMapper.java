package co.com.nequi.ticketing.dynamodb.mapper;

import co.com.nequi.ticketing.dynamodb.entity.InventoryShardEntity;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import org.mapstruct.Mapper;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;

/**
 * Maps {@link InventoryShard} to {@link InventoryShardEntity} and back.
 */
@Mapper(componentModel = "spring")
public interface InventoryShardEntityMapper extends DomainValueConversions {

    InventoryShardEntity toEntity(InventoryShard shard);

    InventoryShard buildShard(InventoryShardEntity entity);

    /**
     * Rebuilds the shard of an item. An item whose counters break the invariant is a data error.
     *
     * @param entity the stored item
     * @return the shard
     */
    default InventoryShard toDomain(InventoryShardEntity entity) {
        try {
            return buildShard(entity);
        } catch (RuntimeException error) {
            throw DYNAMODB_ERROR.build(error);
        }
    }
}
