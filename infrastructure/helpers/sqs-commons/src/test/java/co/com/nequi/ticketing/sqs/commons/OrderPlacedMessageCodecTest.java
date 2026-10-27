package co.com.nequi.ticketing.sqs.commons;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPlacedMessageCodecTest {

    private static final String BODY = """
            {"messageId":"6f0d2c1a-8b3e-4f5a-9c7d-1e2f3a4b5c6d","type":"OrderPlaced","version":1,\
            "occurredAt":"2026-10-27T14:30:00.123Z","data":{"orderId":"9d8e7f6a-5b4c-4d3e-8f2a-1b0c9d8e7f6a",\
            "eventId":"b2c4e6f8-1a3b-4c5d-8e9f-0a1b2c3d4e5f","quantity":2}}""";
    private static final OrderPlacedMessage MESSAGE = new OrderPlacedMessage(
            UUID.fromString("6f0d2c1a-8b3e-4f5a-9c7d-1e2f3a4b5c6d"),
            new OrderId(UUID.fromString("9d8e7f6a-5b4c-4d3e-8f2a-1b0c9d8e7f6a")),
            new EventId(UUID.fromString("b2c4e6f8-1a3b-4c5d-8e9f-0a1b2c3d4e5f")), new Quantity(2),
            Instant.parse("2026-10-27T14:30:00.123Z"));

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OrderPlacedMessageCodec codec = new OrderPlacedMessageCodec(jsonMapper);

    @Test
    void shouldWriteVersionOneContractWhenMessageIsEncoded() {
        // When / Then
        assertThat(jsonMapper.readTree(codec.encode(MESSAGE))).isEqualTo(jsonMapper.readTree(BODY));
    }

    @Test
    void shouldReadMessageWhenBodyFollowsTheContract() {
        // When / Then
        assertThat(codec.decode(BODY)).isEqualTo(MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"Other\"}",
            "not json",
            "{\"messageId\":\"6f0d2c1a-8b3e-4f5a-9c7d-1e2f3a4b5c6d\",\"type\":\"OrderPlaced\",\"version\":2,"
                    + "\"occurredAt\":\"2026-10-27T14:30:00Z\",\"data\":{\"orderId\":\"9d8e7f6a-5b4c-4d3e-8f2a-1b0c9d8e7f6a\","
                    + "\"eventId\":\"b2c4e6f8-1a3b-4c5d-8e9f-0a1b2c3d4e5f\",\"quantity\":2}}",
            "{\"messageId\":\"6f0d2c1a-8b3e-4f5a-9c7d-1e2f3a4b5c6d\",\"type\":\"OrderPlaced\",\"version\":1,"
                    + "\"occurredAt\":\"2026-10-27T14:30:00Z\",\"data\":{\"orderId\":\"not-a-uuid\","
                    + "\"eventId\":\"b2c4e6f8-1a3b-4c5d-8e9f-0a1b2c3d4e5f\",\"quantity\":2}}",
            "{\"messageId\":\"6f0d2c1a-8b3e-4f5a-9c7d-1e2f3a4b5c6d\",\"type\":\"OrderPlaced\",\"version\":1,"
                    + "\"occurredAt\":\"2026-10-27T14:30:00Z\"}"})
    void shouldFailWithInvalidMessageWhenBodyBreaksTheContract(String body) {
        // When / Then
        assertThatThrownBy(() -> codec.decode(body))
                .isInstanceOf(TechnicalException.class)
                .extracting(error -> ((TechnicalException) error).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.INVALID_MESSAGE);
    }
}
