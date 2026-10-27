package co.com.nequi.ticketing.api.order;

import co.com.nequi.ticketing.api.helpers.HeaderParser;
import co.com.nequi.ticketing.api.helpers.RequestHelper;
import co.com.nequi.ticketing.api.order.dto.request.PlaceOrderRequestDto;
import co.com.nequi.ticketing.api.order.mapper.OrderMapManagement;
import co.com.nequi.ticketing.api.order.mapper.OrderMapper;
import co.com.nequi.ticketing.api.security.AuthenticatedUserResolver;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.order.request.GetOrderQuery;
import co.com.nequi.ticketing.model.shared.AuthenticatedUser;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import co.com.nequi.ticketing.usecase.getorder.GetOrderUseCase;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Handlers of the order resource. The identity of the customer is the subject of the bearer token
 * and the purchase is idempotent by {@code Idempotency-Key}. Every purchase counts its result, without
 * customer or order identifiers as tags.
 */
@Component
@RequiredArgsConstructor
public class OrderHandler {

    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final AuthenticatedUserResolver authenticatedUserResolver;
    private final OrderMapper orderMapper;
    private final OrderMapManagement mapManagement;
    private final BeanValidator beanValidator;
    private final MeterRegistry meterRegistry;

    public static final String OUTCOMES_METRIC = "ticketing.orders.outcomes";
    private static final String RESULT_TAG = "result";
    private static final String ACCEPTED_RESULT = "accepted";

    public Mono<ServerResponse> placeOrder(ServerRequest request) {
        return authenticatedUserResolver.resolve()
                .map(AuthenticatedUser::id)
                .zipWith(Mono.fromCallable(() -> HeaderParser.parseIdempotencyKey(
                        header(request, HeaderParser.IDEMPOTENCY_KEY))))
                .flatMap(identity -> RequestHelper.bodyToMonoOrFailWhenEmpty(request, PlaceOrderRequestDto.class)
                        .flatMap(beanValidator::validateBeanOrThrowError)
                        .map(body -> orderMapper.toCommand(identity.getT1(), identity.getT2(), body)))
                .flatMap(placeOrderUseCase::place)
                .doOnNext(accepted -> countOutcome(ACCEPTED_RESULT))
                .doOnError(BusinessException.class,
                        rejected -> countOutcome(rejected.getBusinessErrorMessage().getCode()))
                .map(orderMapper::toResponse)
                .map(accepted -> mapManagement.buildResponse(accepted, SuccessMessage.ACCEPTED))
                .flatMap(body -> ServerResponse.status(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body));
    }

    public Mono<ServerResponse> getOrder(ServerRequest request) {
        return authenticatedUserResolver.resolve()
                .flatMap(user -> Mono.fromCallable(() -> new GetOrderQuery(user.id(),
                        HeaderParser.parseOrderId(request.pathVariable(HeaderParser.ORDER_ID)))))
                .flatMap(getOrderUseCase::get)
                .map(orderMapper::toResponse)
                .map(order -> mapManagement.buildResponse(order, SuccessMessage.SUCCESSFUL))
                .flatMap(body -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body));
    }

    private void countOutcome(String result) {
        meterRegistry.counter(OUTCOMES_METRIC, RESULT_TAG, result).increment();
    }

    private static String header(ServerRequest request, String name) {
        return request.headers().firstHeader(name);
    }
}
