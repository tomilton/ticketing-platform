package co.com.nequi.ticketing.model.payment;

import co.com.nequi.ticketing.model.config.PaymentNotificationSettings;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentNotificationTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final OrderId ORDER_ID = OrderId.newId();
    private static final Instant AT = Instant.parse("2026-10-27T15:00:00Z");

    @Test
    void shouldThrowNullPointerExceptionWhenComponentIsNull() {
        // When / Then
        assertThatThrownBy(() -> new PaymentNotification(null, ORDER_ID, PaymentResult.APPROVED, AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentNotification(EVENT_ID, null, PaymentResult.APPROVED, AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentNotification(EVENT_ID, ORDER_ID, null, AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentNotification(EVENT_ID, ORDER_ID, PaymentResult.APPROVED, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectSettingsWhenRetentionIsNotPositive() {
        // When / Then
        assertThatThrownBy(() -> new PaymentNotificationSettings(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentNotificationSettings(null))
                .isInstanceOf(NullPointerException.class);
    }
}
