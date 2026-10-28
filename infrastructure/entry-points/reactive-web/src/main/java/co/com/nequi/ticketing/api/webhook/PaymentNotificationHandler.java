package co.com.nequi.ticketing.api.webhook;

import co.com.nequi.ticketing.api.commons.Meta;
import co.com.nequi.ticketing.api.commons.Response;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.api.webhook.dto.PaymentNotificationRequestDto;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import co.com.nequi.ticketing.usecase.receivepaymentnotification.ReceivePaymentNotificationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.stream.Stream;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.EMPTY_REQUEST_BODY;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.INVALID_JSON;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.INVALID_WEBHOOK_SIGNATURE;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.PAYLOAD_TOO_LARGE;

/**
 * Handler of the notices of the payment gateway. The signature is verified on the raw body before
 * anything is read from it; only then the body is parsed and validated. Every authentic notice,
 * a copy included, answers 200, so the gateway stops sending it.
 */
@Component
@RequiredArgsConstructor
public class PaymentNotificationHandler {

    public static final String TIMESTAMP_HEADER = "X-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Signature";
    private static final byte[] NO_BODY = new byte[0];

    private final WebhookSignatureVerifier verifier;
    private final ReceivePaymentNotificationUseCase receivePaymentNotificationUseCase;
    private final ClockProvider clockProvider;
    private final ObjectMapper objectMapper;
    private final BeanValidator beanValidator;

    public Mono<ServerResponse> receive(ServerRequest request) {
        return request.bodyToMono(byte[].class)
                .onErrorMap(PaymentNotificationHandler::exceedsMaximumSize, PAYLOAD_TOO_LARGE::build)
                .defaultIfEmpty(NO_BODY)
                .filter(body -> verifier.isAuthentic(request.headers().firstHeader(TIMESTAMP_HEADER),
                        request.headers().firstHeader(SIGNATURE_HEADER), body, clockProvider.now()))
                .switchIfEmpty(Mono.defer(() -> Mono.error(INVALID_WEBHOOK_SIGNATURE.build())))
                .filter(body -> body.length > 0)
                .switchIfEmpty(Mono.defer(() -> Mono.error(EMPTY_REQUEST_BODY.build())))
                .map(this::read)
                .flatMap(beanValidator::validateBeanOrThrowError)
                .map(PaymentNotificationHandler::toNotification)
                .flatMap(receivePaymentNotificationUseCase::receive)
                .then(Mono.defer(() -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new Response<>(Meta.getSuccessMeta(SuccessMessage.SUCCESSFUL), null))));
    }

    private PaymentNotificationRequestDto read(byte[] body) {
        try {
            return objectMapper.readValue(body, PaymentNotificationRequestDto.class);
        } catch (JacksonException error) {
            throw INVALID_JSON.build(error);
        }
    }

    private static PaymentNotification toNotification(PaymentNotificationRequestDto request) {
        return new PaymentNotification(request.eventId(), new OrderId(request.orderId()),
                PaymentResult.valueOf(request.status()), request.occurredAt());
    }

    private static Boolean exceedsMaximumSize(Throwable error) {
        return Stream.iterate(error, cause -> cause != null, Throwable::getCause)
                .anyMatch(DataBufferLimitException.class::isInstance);
    }
}
