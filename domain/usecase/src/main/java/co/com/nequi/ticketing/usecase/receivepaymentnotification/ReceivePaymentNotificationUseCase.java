package co.com.nequi.ticketing.usecase.receivepaymentnotification;

import co.com.nequi.ticketing.model.config.PaymentNotificationSettings;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.model.payment.gateways.PaymentNotificationPublisher;
import co.com.nequi.ticketing.model.payment.gateways.PaymentNotificationRepository;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Receives an authentic notice of the payment gateway. A notice already received is dropped; a new
 * one is published for the worker and then registered, so a failure before the register makes the
 * gateway send it again. Two copies that arrive at the same time may both be published: the worker
 * applies a notice by the current status of the order, so the second one has no effect.
 */
@RequiredArgsConstructor
public class ReceivePaymentNotificationUseCase {

    private final PaymentNotificationRepository paymentNotificationRepository;
    private final PaymentNotificationPublisher paymentNotificationPublisher;
    private final ClockProvider clockProvider;
    private final PaymentNotificationSettings settings;

    public Mono<Void> receive(PaymentNotification notification) {
        return paymentNotificationRepository.exists(notification.eventId())
                .filter(Boolean.FALSE::equals)
                .flatMap(isNew -> paymentNotificationPublisher.publish(notification)
                        .then(Mono.defer(() -> paymentNotificationRepository.register(notification,
                                clockProvider.now().plus(settings.retention())))))
                .then();
    }
}
