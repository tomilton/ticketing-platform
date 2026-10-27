package co.com.nequi.ticketing.performance;

import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-002: p99 under 150 ms for {@code POST /orders} with 20 purchases in flight, against LocalStack.
 * It runs with {@code ./gradlew performanceTest}, outside the regular build.
 */
@Tag("performance")
@AutoConfigureWebTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.co.com.nequi.ticketing.dynamodb=INFO")
class PlaceOrderLatencyTest {

    private static final Logger LOG = LoggerFactory.getLogger(PlaceOrderLatencyTest.class);
    private static final String EVENTS = "/api/v1/events";
    private static final String ORDERS = "/api/v1/orders";
    private static final String EVENT_BODY = "{\"name\":\"Latency\",\"venue\":{\"name\":\"Movistar Arena\","
            + "\"city\":\"Bogota\"},\"date\":\"2030-03-20T01:00:00Z\",\"capacity\":5000}";
    private static final int WARM_UP_REQUESTS = 100;
    private static final int MEASURED_REQUESTS = 1_000;
    private static final int CONCURRENCY = 20;
    private static final double PERCENTILE = 0.99;
    private static final Duration MAX_P99 = Duration.ofMillis(150);

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void localStackProperties(DynamicPropertyRegistry registry) {
        LocalStackTestSupport.springProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @Test
    void shouldAnswerUnderOneHundredFiftyMillisecondsAtP99WhenOrdersArePlaced() throws Exception {
        // Given
        String eventId = JsonMapper.builder().build().readTree(webTestClient.post().uri(EVENTS)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(EVENT_BODY).exchange()
                        .expectStatus().isCreated()
                        .expectBody(String.class).returnResult().getResponseBody())
                .path("data").path("eventId").asString();

        // When
        Duration p99;
        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY)) {
            run(executor, eventId, WARM_UP_REQUESTS);
            List<Long> latencies = run(executor, eventId, MEASURED_REQUESTS).stream().sorted().toList();
            p99 = Duration.ofNanos(latencies.get((int) Math.ceil(MEASURED_REQUESTS * PERCENTILE) - 1));
        }
        LOG.info("SC-002 p99 POST /orders: {} ms", p99.toMillis());

        // Then
        assertThat(p99).isLessThan(MAX_P99);
    }

    private List<Long> run(ExecutorService executor, String eventId, int requests) throws Exception {
        List<Callable<Long>> purchases = IntStream.range(0, requests)
                .<Callable<Long>>mapToObj(index -> () -> timedPurchase(eventId))
                .toList();
        List<Long> latencies = new ArrayList<>();
        for (Future<Long> latency : executor.invokeAll(purchases)) {
            latencies.add(latency.get());
        }
        return latencies;
    }

    private Long timedPurchase(String eventId) {
        long start = System.nanoTime();
        webTestClient.post().uri(ORDERS)
                .header("X-Customer-Id", UUID.randomUUID().toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"eventId\":\"" + eventId + "\",\"quantity\":1}")
                .exchange()
                .expectStatus().isAccepted();
        return System.nanoTime() - start;
    }
}
