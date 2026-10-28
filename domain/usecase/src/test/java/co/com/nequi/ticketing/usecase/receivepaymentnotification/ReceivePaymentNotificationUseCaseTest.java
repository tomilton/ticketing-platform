package co.com.nequi.ticketing.usecase.receivepaymentnotification;

import co.com.nequi.ticketing.model.config.PaymentNotificationSettings;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.model.payment.gateways.PaymentNotificationPublisher;
import co.com.nequi.ticketing.model.payment.gateways.PaymentNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceivePaymentNotificationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-27T15:00:05Z");
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final PaymentNotification NOTIFICATION = new PaymentNotification(UUID.randomUUID(),
            OrderId.newId(), PaymentResult.APPROVED, Instant.parse("2026-10-27T15:00:00Z"));

    @Mock
    private PaymentNotificationRepository repository;

    @Mock
    private PaymentNotificationPublisher publisher;

    private ReceivePaymentNotificationUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ReceivePaymentNotificationUseCase(repository, publisher, () -> NOW,
                new PaymentNotificationSettings(RETENTION));
    }

    @Test
    void shouldPublishAndRegisterWhenNoticeIsNew() {
        // Given
        when(repository.exists(NOTIFICATION.eventId())).thenReturn(Mono.just(Boolean.FALSE));
        when(publisher.publish(NOTIFICATION)).thenReturn(Mono.empty());
        when(repository.register(NOTIFICATION, NOW.plus(RETENTION))).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.receive(NOTIFICATION)).verifyComplete();
        verify(repository).register(NOTIFICATION, NOW.plus(RETENTION));
    }

    @Test
    void shouldDropWithoutPublishingWhenNoticeWasAlreadyReceived() {
        // Given
        when(repository.exists(NOTIFICATION.eventId())).thenReturn(Mono.just(Boolean.TRUE));

        // When / Then
        StepVerifier.create(useCase.receive(NOTIFICATION)).verifyComplete();
        verifyNoInteractions(publisher);
        verify(repository, never()).register(any(), any());
    }

    @Test
    void shouldNotRegisterWhenPublicationFails() {
        // Given
        IllegalStateException failure = new IllegalStateException("queue unavailable");
        when(repository.exists(NOTIFICATION.eventId())).thenReturn(Mono.just(Boolean.FALSE));
        when(publisher.publish(NOTIFICATION)).thenReturn(Mono.error(failure));

        // When / Then
        StepVerifier.create(useCase.receive(NOTIFICATION))
                .expectErrorMatches(error -> error == failure)
                .verify();
        verify(repository, never()).register(any(), any());
    }
}
