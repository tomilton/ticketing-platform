package co.com.nequi.ticketing.dynamodb.entity;

import co.com.nequi.ticketing.model.enums.EventStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * Item of the events table. The date is stored in milliseconds since epoch so the index keeps
 * the chronological order.
 */
@Getter
@Setter
@NoArgsConstructor
@DynamoDbBean
public class EventEntity {

    public static final String STATUS_DATE_INDEX = "by-status-date";

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String eventId;
    private String name;
    private String venueName;
    private String city;
    @Getter(onMethod_ = @DynamoDbSecondarySortKey(indexNames = STATUS_DATE_INDEX))
    private Long date;
    private Integer capacity;
    @Getter(onMethod_ = @DynamoDbSecondaryPartitionKey(indexNames = STATUS_DATE_INDEX))
    private EventStatus status;
    private Integer shardCount;
    private Long version;
}
