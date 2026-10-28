package co.com.nequi.ticketing.api.webhook;

import co.com.nequi.ticketing.api.config.JsonMapperConfig;
import co.com.nequi.ticketing.api.config.RouteProperties;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorResponseBuilder;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorStatusMapper;
import co.com.nequi.ticketing.api.handle.exceptions.GlobalErrorWebExceptionHandler;
import co.com.nequi.ticketing.api.support.SecurityTestConfig;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import co.com.nequi.ticketing.usecase.receivepaymentnotification.ReceivePaymentNotificationUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest
@ContextConfiguration(classes = PaymentWebhookRouteTest.WebhookRouteTestConfig.class)
class PaymentWebhookRouteTest {

    private static final String PATH = "/api/v1/webhooks/payments";
    private static final String SECRET = "test-only-webhook-secret-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-27T15:00:05Z");
    private static final String TIMESTAMP = String.valueOf(NOW.getEpochSecond());
    private static final String EVENT = "6f1c1c8e-2b55-4a0e-9a34-6c1f1c2f0b11";
    private static final String ORDER = "0b8f2c1e-7d33-4c84-b1f2-2a0d8e5c9f10";
    private static final String BODY = "{\"eventId\":\"" + EVENT + "\",\"orderId\":\"" + ORDER
            + "\",\"status\":\"APPROVED\",\"occurredAt\":\"2026-10-27T15:00:00Z\"}";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReceivePaymentNotificationUseCase receivePaymentNotificationUseCase;

    @Test
    void shouldAcceptAndForwardNoticeWithoutTokenWhenSignatureIsValid() {
        // Given
        PaymentNotification expected = new PaymentNotification(UUID.fromString(EVENT),
                new OrderId(UUID.fromString(ORDER)), PaymentResult.APPROVED, Instant.parse("2026-10-27T15:00:00Z"));
        when(receivePaymentNotificationUseCase.receive(expected)).thenReturn(Mono.empty());

        // When / Then
        post(TIMESTAMP, sign(TIMESTAMP, BODY), BODY)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.meta.code").isEqualTo("TKS0001");
        verify(receivePaymentNotificationUseCase).receive(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"NONE, NONE", "VALID, 00ff", "OLD, VALID", "yesterday, VALID"}, nullValues = "NONE")
    void shouldRejectWithInvalidSignatureWithoutForwardingWhenNoticeIsNotAuthentic(String timestamp, String signature) {
        // Given
        String sentAt = switch (String.valueOf(timestamp)) {
            case "VALID" -> TIMESTAMP;
            case "OLD" -> String.valueOf(NOW.getEpochSecond() - 301);
            default -> timestamp;
        };
        String signed = "VALID".equals(signature) && sentAt != null ? sign(sentAt, BODY) : signature;

        // When / Then
        post(sentAt, signed, BODY)
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo("TKT0013");
        verifyNoInteractions(receivePaymentNotificationUseCase);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"eventId\": | TKT0002",
            "{\"eventId\":\"" + EVENT + "\",\"orderId\":\"" + ORDER + "\",\"status\":\"MAYBE\",\"occurredAt\":\"2026-10-27T15:00:00Z\"} | TKT0001",
            "{\"orderId\":\"" + ORDER + "\",\"status\":\"APPROVED\",\"occurredAt\":\"2026-10-27T15:00:00Z\"} | TKT0001"})
    void shouldRejectWithBadRequestWhenSignedBodyIsInvalid(String body, String code) {
        // When / Then
        post(TIMESTAMP, sign(TIMESTAMP, body), body)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo(code);
        verifyNoInteractions(receivePaymentNotificationUseCase);
    }

    @Test
    void shouldRejectWithEmptyBodyWhenSignedBodyIsMissing() {
        // When / Then
        webTestClient.post().uri(PATH)
                .header(PaymentNotificationHandler.TIMESTAMP_HEADER, TIMESTAMP)
                .header(PaymentNotificationHandler.SIGNATURE_HEADER, sign(TIMESTAMP, ""))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo("TKT0003");
    }

    @Test
    void shouldAnswerServerErrorWhenNoticeCannotBeQueued() {
        // Given
        when(receivePaymentNotificationUseCase.receive(any(PaymentNotification.class)))
                .thenReturn(Mono.error(new IllegalStateException("queue unavailable")));

        // When / Then
        post(TIMESTAMP, sign(TIMESTAMP, BODY), BODY).expectStatus().is5xxServerError();
    }

    private WebTestClient.ResponseSpec post(String timestamp, String signature, String body) {
        return webTestClient.post().uri(PATH)
                .headers(headers -> {
                    if (timestamp != null) {
                        headers.set(PaymentNotificationHandler.TIMESTAMP_HEADER, timestamp);
                    }
                    if (signature != null) {
                        headers.set(PaymentNotificationHandler.SIGNATURE_HEADER, signature);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    @Configuration
    @EnableConfigurationProperties(RouteProperties.class)
    @Import({SecurityTestConfig.class, PaymentWebhookRouterRest.class, PaymentNotificationHandler.class,
            WebhookSignatureVerifier.class, BeanValidator.class, GlobalErrorWebExceptionHandler.class,
            ErrorResponseBuilder.class, ErrorStatusMapper.class, JsonMapperConfig.class})
    static class WebhookRouteTestConfig {

        @Bean
        ClockProvider clockProvider() {
            return () -> NOW;
        }
    }
}
