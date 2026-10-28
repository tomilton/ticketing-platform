package co.com.nequi.ticketing.sqs.commons;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentConfirmedMessageCodecTest {

    private static final PaymentNotification NOTIFICATION = new PaymentNotification(
            UUID.fromString("6f1c1c8e-2b55-4a0e-9a34-6c1f1c2f0b11"),
            new OrderId(UUID.fromString("0b8f2c1e-7d33-4c84-b1f2-2a0d8e5c9f10")), PaymentResult.APPROVED,
            Instant.parse("2026-10-27T15:00:00Z"));

    private final PaymentConfirmedMessageCodec codec = new PaymentConfirmedMessageCodec(JsonMapper.builder().build());

    @Test
    void shouldReadSameNoticeWhenBodyWasWrittenByCodec() {
        // When / Then
        assertThat(codec.decode(codec.encode(NOTIFICATION))).isEqualTo(NOTIFICATION);
        assertThat(codec.encode(NOTIFICATION)).contains("\"type\":\"PaymentConfirmed\"", "\"version\":1",
                "\"status\":\"APPROVED\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"type\":\"Other\",\"version\":1}", "{\"type\":\"PaymentConfirmed\",\"version\":2}",
            "{\"eventId\":\"6f1c1c8e-2b55-4a0e-9a34-6c1f1c2f0b11\",\"type\":\"PaymentConfirmed\",\"version\":1,"
                    + "\"orderId\":\"0b8f2c1e-7d33-4c84-b1f2-2a0d8e5c9f10\",\"status\":\"MAYBE\","
                    + "\"occurredAt\":\"2026-10-27T15:00:00Z\"}"})
    void shouldFailWithInvalidMessageWhenBodyBreaksContract(String body) {
        // When / Then
        assertThatThrownBy(() -> codec.decode(body))
                .isInstanceOf(TechnicalException.class)
                .extracting(error -> ((TechnicalException) error).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.INVALID_MESSAGE);
    }
}
