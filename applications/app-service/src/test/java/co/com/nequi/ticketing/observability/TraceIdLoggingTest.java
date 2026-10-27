package co.com.nequi.ticketing.observability;

import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constitution II and XIV: the trace id travels in the Reactor context and reaches the JSON logs
 * of the driven adapters.
 */
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureWebTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceIdLoggingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REPOSITORY_LOGGER = "co.com.nequi.ticketing.dynamodb.EventDynamoRepository";
    private static final String BODY = "{\"name\":\"Rock al Parque\",\"venue\":{\"name\":\"Movistar Arena\","
            + "\"city\":\"Bogota\"},\"date\":\"2030-03-20T01:00:00Z\",\"capacity\":10}";

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void dynamoDbProperties(DynamicPropertyRegistry registry) {
        LocalStackTestSupport.springProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @Test
    void shouldLogSameNonEmptyTraceIdInRepositoryLinesWhenRequestIsHandled(CapturedOutput output) {
        // Given
        String eventId = JSON.readTree(webTestClient.post().uri("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY).exchange()
                        .expectStatus().isCreated()
                        .expectBody(String.class).returnResult().getResponseBody())
                .path("data").path("eventId").asString();
        int createOutputLength = output.getOut().length();

        // When
        webTestClient.get().uri("/api/v1/events/" + eventId).exchange().expectStatus().isOk();

        // Then
        List<String> createTraceIds = repositoryTraceIds(output.getOut().substring(0, createOutputLength), eventId);
        List<String> getTraceIds = repositoryTraceIds(output.getOut().substring(createOutputLength), eventId);
        assertThat(createTraceIds).isNotEmpty().allSatisfy(traceId -> assertThat(traceId).isNotBlank());
        assertThat(getTraceIds).isNotEmpty().allSatisfy(traceId -> assertThat(traceId).isNotBlank());
        assertThat(createTraceIds).containsOnly(createTraceIds.getFirst());
        assertThat(getTraceIds).containsOnly(getTraceIds.getFirst());
        assertThat(getTraceIds.getFirst()).isNotEqualTo(createTraceIds.getFirst());
    }

    private static List<String> repositoryTraceIds(String output, String eventId) {
        return output.lines()
                .filter(line -> line.startsWith("{") && line.contains(REPOSITORY_LOGGER) && line.contains(eventId))
                .map(JSON::readTree)
                .map(TraceIdLoggingTest::traceId)
                .toList();
    }

    private static String traceId(JsonNode line) {
        return Optional.ofNullable(line.get("traceId"))
                .or(() -> Optional.ofNullable(line.get("trace.id")))
                .or(() -> Optional.ofNullable(line.path("trace").get("id")))
                .map(JsonNode::asString)
                .orElse("");
    }
}
