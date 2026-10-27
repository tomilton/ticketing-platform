package co.com.nequi.ticketing.sqs.commons;

import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.sqs.commons.dto.OrderPlacedDataDto;
import co.com.nequi.ticketing.sqs.commons.dto.OrderPlacedPayloadDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.INVALID_MESSAGE;

/**
 * Writes and reads the body of the {@code OrderPlaced} message, version 1. It is shared by the
 * publisher and the listener so both sides use the same contract. A body that breaks the contract
 * is a permanent error.
 */
@Component
@RequiredArgsConstructor
public class OrderPlacedMessageCodec {

    public static final String TYPE = "OrderPlaced";
    public static final Integer VERSION = 1;

    private final ObjectMapper objectMapper;

    /**
     * Writes the JSON body of a message.
     *
     * @param message the accepted order
     * @return the body of version 1
     */
    public String encode(OrderPlacedMessage message) {
        return objectMapper.writeValueAsString(new OrderPlacedPayloadDto(message.messageId(), TYPE, VERSION,
                message.occurredAt(), new OrderPlacedDataDto(message.orderId().value(), message.eventId().value(),
                message.quantity().value())));
    }

    /**
     * Reads the JSON body of a message.
     *
     * @param body the body received from the queue
     * @return the accepted order
     * @throws co.com.nequi.ticketing.commons.exception.TechnicalException with {@code INVALID_MESSAGE} if the
     *                                                                     body breaks the contract
     */
    public OrderPlacedMessage decode(String body) {
        try {
            OrderPlacedPayloadDto payload = objectMapper.readValue(body, OrderPlacedPayloadDto.class);
            if (!TYPE.equals(payload.type()) || !VERSION.equals(payload.version())) {
                throw new IllegalArgumentException("Unknown message type or version");
            }
            OrderPlacedDataDto data = Objects.requireNonNull(payload.data(), "Data must not be null");
            return new OrderPlacedMessage(payload.messageId(), new OrderId(data.orderId()),
                    new EventId(data.eventId()), new Quantity(data.quantity()), payload.occurredAt());
        } catch (RuntimeException error) {
            throw INVALID_MESSAGE.build(error);
        }
    }
}
