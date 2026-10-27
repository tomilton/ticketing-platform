package co.com.nequi.ticketing.dynamodb.entity;

import co.com.nequi.ticketing.dynamodb.converter.OrderStatusConverter;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.util.List;

/**
 * Item of the orders table. Instants are stored in milliseconds since epoch. {@code activeBucket}
 * exists only while the order is active and {@code publishBucket} only until it is published, so
 * their indexes are sparse.
 */
@Getter
@Setter
@NoArgsConstructor
@DynamoDbBean
public class OrderEntity {

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String orderId;
    private String customerId;
    private String eventId;
    private Integer quantity;
    private List<String> tickets;
    @Getter(onMethod_ = @DynamoDbConvertedBy(OrderStatusConverter.class))
    private OrderStatus status;
    private Long createdAt;
    private Long expiresAt;
    private Integer shardId;
    private String activeBucket;
    private String publishBucket;
    private Long publishedAt;
    private Long version;
}
