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
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-001: p99 under 100 ms for the event and its availability, against LocalStack. It runs with
 * {@code ./gradlew performanceTest}, outside the regular build.
 */
@Tag("performance")
@AutoConfigureWebTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.co.com.nequi.ticketing.dynamodb=INFO")
class AvailabilityLatencyTest {

    private static final Logger LOG = LoggerFactory.getLogger(AvailabilityLatencyTest.class);
    private static final String EVENTS = "/api/v1/events";
    private static final String BODY = "{\"name\":\"Latency\",\"venue\":{\"name\":\"Movistar Arena\","
            + "\"city\":\"Bogota\"},\"date\":\"2030-03-20T01:00:00Z\",\"capacity\":500}";
    private static final int WARM_UP_REQUESTS = 100;
    private static final int MEASURED_REQUESTS = 1_000;
    private static final double PERCENTILE = 0.99;
    private static final Duration MAX_P99 = Duration.ofMillis(100);

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void dynamoDbProperties(DynamicPropertyRegistry registry) {
        LocalStackTestSupport.springProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @Test
    void shouldAnswerUnderOneHundredMillisecondsAtP99WhenEventAndAvailabilityAreRead() {
        // Given
        String eventId = JsonMapper.builder().build().readTree(webTestClient.post().uri(EVENTS)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY).exchange()
                        .expectStatus().isCreated()
                        .expectBody(String.class).returnResult().getResponseBody())
                .path("data").path("eventId").asString();

        // When
        Duration eventP99 = p99(EVENTS + "/" + eventId);
        Duration availabilityP99 = p99(EVENTS + "/" + eventId + "/availability");
        LOG.info("SC-001 p99 GET /events/{id}: {} ms", eventP99.toMillis());
        LOG.info("SC-001 p99 GET /events/{id}/availability: {} ms", availabilityP99.toMillis());

        // Then
        assertThat(eventP99).isLessThan(MAX_P99);
        assertThat(availabilityP99).isLessThan(MAX_P99);
    }

    private Duration p99(String uri) {
        IntStream.range(0, WARM_UP_REQUESTS).forEach(index -> get(uri));
        List<Long> latencies = IntStream.range(0, MEASURED_REQUESTS)
                .mapToObj(index -> timed(uri))
                .sorted()
                .toList();
        return Duration.ofNanos(latencies.get((int) Math.ceil(MEASURED_REQUESTS * PERCENTILE) - 1));
    }

    private Long timed(String uri) {
        long start = System.nanoTime();
        get(uri);
        return System.nanoTime() - start;
    }

    private void get(String uri) {
        webTestClient.get().uri(uri).exchange().expectStatus().isOk();
    }
}
