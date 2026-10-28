package co.com.nequi.ticketing.sqs.commons;

import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.sqs.commons.dto.PaymentConfirmedPayloadDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.INVALID_MESSAGE;

/**
 * Writes and reads the body of the {@code PaymentConfirmed} message, version 1, with a notice of the
 * payment gateway. It is shared by the publisher of the API and the listener of the worker. A body
 * that breaks the contract is a permanent error.
 */
@Component
@RequiredArgsConstructor
public class PaymentConfirmedMessageCodec {

    public static final String TYPE = "PaymentConfirmed";
    public static final Integer VERSION = 1;

    private final ObjectMapper objectMapper;

    /**
     * Writes the JSON body of a notice.
     *
     * @param notification the notice of the gateway
     * @return the body of version 1
     */
    public String encode(PaymentNotification notification) {
        return objectMapper.writeValueAsString(new PaymentConfirmedPayloadDto(notification.eventId(), TYPE, VERSION,
                notification.orderId().value(), notification.status().name(), notification.occurredAt()));
    }

    /**
     * Reads the JSON body of a notice.
     *
     * @param body the body received from the queue
     * @return the notice of the gateway
     * @throws co.com.nequi.ticketing.commons.exception.TechnicalException with {@code INVALID_MESSAGE} if the
     *                                                                     body breaks the contract
     */
    public PaymentNotification decode(String body) {
        try {
            PaymentConfirmedPayloadDto payload = objectMapper.readValue(body, PaymentConfirmedPayloadDto.class);
            if (!TYPE.equals(payload.type()) || !VERSION.equals(payload.version())) {
                throw new IllegalArgumentException("Unknown message type or version");
            }
            return new PaymentNotification(payload.eventId(), new OrderId(payload.orderId()),
                    PaymentResult.valueOf(payload.status()), payload.occurredAt());
        } catch (RuntimeException error) {
            throw INVALID_MESSAGE.build(error);
        }
    }
}
