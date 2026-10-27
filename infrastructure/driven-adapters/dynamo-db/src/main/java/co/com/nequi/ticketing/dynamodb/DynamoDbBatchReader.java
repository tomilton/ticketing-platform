package co.com.nequi.ticketing.dynamodb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;

/**
 * Strongly consistent {@code BatchGetItem} over one table. Unprocessed keys are requested again
 * with exponential backoff; if some remain after the last retry the read fails, so a partial
 * result is never returned as complete.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamoDbBatchReader {

    private static final int MAX_RETRIES = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofMillis(100);

    private final DynamoDbAsyncClient client;

    public Flux<Map<String, AttributeValue>> read(String tableName, List<Map<String, AttributeValue>> keys) {
        return fetch(tableName, KeysAndAttributes.builder().keys(keys).consistentRead(Boolean.TRUE).build(), 0);
    }

    private Flux<Map<String, AttributeValue>> fetch(String tableName, KeysAndAttributes keys, Integer retry) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB BatchGetItem, table={}, keys={}, retry={}", tableName, keys.keys().size(), retry);
                    return client.batchGetItem(BatchGetItemRequest.builder().requestItems(Map.of(tableName, keys)).build());
                })
                .retryWhen(DynamoDbRetryPolicy.retrySpec())
                .flatMapMany(response -> Flux.fromIterable(response.responses().getOrDefault(tableName, List.of()))
                        .concatWith(fetchUnprocessed(tableName, response, retry)));
    }

    private Flux<Map<String, AttributeValue>> fetchUnprocessed(String tableName, BatchGetItemResponse response,
                                                               Integer retry) {
        return Mono.justOrEmpty(response.unprocessedKeys().get(tableName))
                .filter(unprocessed -> !unprocessed.keys().isEmpty())
                .flatMapMany(unprocessed -> Mono.just(retry)
                        .filter(attempt -> attempt < MAX_RETRIES)
                        .switchIfEmpty(Mono.defer(() -> Mono.error(DYNAMODB_ERROR.build())))
                        .flatMapMany(attempt -> Mono.delay(FIRST_BACKOFF.multipliedBy(1L << attempt))
                                .thenMany(fetch(tableName, unprocessed, attempt + 1))));
    }
}
