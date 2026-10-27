package co.com.nequi.ticketing.dynamodb.health;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;

/**
 * Health of DynamoDB, exposed as the {@code dynamoDb} component of {@code /actuator/health}. The
 * details never include SDK messages or resource names.
 */
@Slf4j
@RequiredArgsConstructor
@Component("dynamoDb")
public class DynamoDbHealthIndicator implements ReactiveHealthIndicator {

    private static final String EVENTS_DETAIL = "events";
    private static final String INVENTORY_DETAIL = "inventory";
    private static final String CODE_DETAIL = "code";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;

    @Override
    public Mono<Health> health() {
        return Mono.zip(describeTable(properties.eventsTable()), describeTable(properties.inventoryTable()))
                .map(statuses -> buildHealth(statuses.getT1(), statuses.getT2()))
                .timeout(properties.healthCheckTimeout())
                .onErrorResume(error -> {
                    log.warn("DynamoDB health check failed, code={}", DYNAMODB_ERROR.getCode(), error);
                    return Mono.just(Health.down().withDetail(CODE_DETAIL, DYNAMODB_ERROR.getCode()).build());
                });
    }

    private Mono<TableStatus> describeTable(String tableName) {
        log.debug("DynamoDB DescribeTable, table={}", tableName);
        return Mono.fromFuture(() -> client.describeTable(DescribeTableRequest.builder().tableName(tableName).build()))
                .map(response -> response.table().tableStatus());
    }

    private static Health buildHealth(TableStatus eventsStatus, TableStatus inventoryStatus) {
        Status status = isActive(eventsStatus) && isActive(inventoryStatus) ? Status.UP : Status.DOWN;
        return Health.status(status)
                .withDetail(EVENTS_DETAIL, eventsStatus.toString())
                .withDetail(INVENTORY_DETAIL, inventoryStatus.toString())
                .build();
    }

    private static boolean isActive(TableStatus status) {
        return status == TableStatus.ACTIVE;
    }
}
