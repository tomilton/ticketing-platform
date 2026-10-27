package co.com.nequi.ticketing.dynamodb.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Item of the inventory table: one shard of the inventory of an event.
 */
@Getter
@Setter
@NoArgsConstructor
@DynamoDbBean
public class InventoryShardEntity {

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String eventId;
    @Getter(onMethod_ = @DynamoDbSortKey)
    private Integer shardId;
    private Integer capacity;
    private Integer available;
    private Integer reserved;
    private Integer sold;
    private Integer complimentary;
    private Long version;
}
