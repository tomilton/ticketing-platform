package co.com.nequi.ticketing.api.order;

import co.com.nequi.ticketing.api.helpers.HeaderParser;
import co.com.nequi.ticketing.api.helpers.RequestHelper;
import co.com.nequi.ticketing.api.order.dto.request.PlaceOrderRequestDto;
import co.com.nequi.ticketing.api.order.mapper.OrderMapManagement;
import co.com.nequi.ticketing.api.order.mapper.OrderMapper;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.model.order.request.GetOrderQuery;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import co.com.nequi.ticketing.usecase.getorder.GetOrderUseCase;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Handlers of the order resource. The identity of the customer comes from {@code X-Customer-Id}
 * and the purchase is idempotent by {@code Idempotency-Key}.
 */
@Component
@RequiredArgsConstructor
public class OrderHandler {

    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final OrderMapper orderMapper;
    private final OrderMapManagement mapManagement;
    private final BeanValidator beanValidator;

    public Mono<ServerResponse> placeOrder(ServerRequest request) {
        return Mono.fromCallable(() -> HeaderParser.parseCustomerId(header(request, HeaderParser.CUSTOMER_ID)))
                .zipWith(Mono.fromCallable(() -> HeaderParser.parseIdempotencyKey(
                        header(request, HeaderParser.IDEMPOTENCY_KEY))))
                .flatMap(identity -> RequestHelper.bodyToMonoOrFailWhenEmpty(request, PlaceOrderRequestDto.class)
                        .flatMap(beanValidator::validateBeanOrThrowError)
                        .map(body -> orderMapper.toCommand(identity.getT1(), identity.getT2(), body)))
                .flatMap(placeOrderUseCase::place)
                .map(orderMapper::toResponse)
                .map(accepted -> mapManagement.buildResponse(accepted, SuccessMessage.ACCEPTED))
                .flatMap(body -> ServerResponse.status(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body));
    }

    public Mono<ServerResponse> getOrder(ServerRequest request) {
        return Mono.fromCallable(() -> new GetOrderQuery(
                        HeaderParser.parseCustomerId(header(request, HeaderParser.CUSTOMER_ID)),
                        HeaderParser.parseOrderId(request.pathVariable(HeaderParser.ORDER_ID))))
                .flatMap(getOrderUseCase::get)
                .map(orderMapper::toResponse)
                .map(order -> mapManagement.buildResponse(order, SuccessMessage.SUCCESSFUL))
                .flatMap(body -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body));
    }

    private static String header(ServerRequest request, String name) {
        return request.headers().firstHeader(name);
    }
}
