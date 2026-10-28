package co.com.nequi.ticketing.api.order;

import co.com.nequi.ticketing.api.helpers.HeaderParser;
import co.com.nequi.ticketing.api.helpers.QueryParameterParser;
import co.com.nequi.ticketing.api.helpers.RequestHelper;
import co.com.nequi.ticketing.api.order.dto.request.GrantComplimentaryRequestDto;
import co.com.nequi.ticketing.api.order.mapper.OrderMapManagement;
import co.com.nequi.ticketing.api.order.mapper.OrderMapper;
import co.com.nequi.ticketing.api.security.AuthenticatedUserResolver;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import co.com.nequi.ticketing.usecase.grantcomplimentary.GrantComplimentaryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Handler of the complimentary tickets of an event. The security filter chain lets only an
 * administrator reach it; the grant is idempotent by {@code Idempotency-Key} in the scope of that
 * administrator.
 */
@Component
@RequiredArgsConstructor
public class ComplimentaryHandler {

    private final GrantComplimentaryUseCase grantComplimentaryUseCase;
    private final AuthenticatedUserResolver authenticatedUserResolver;
    private final OrderMapper orderMapper;
    private final OrderMapManagement mapManagement;
    private final BeanValidator beanValidator;

    public Mono<ServerResponse> grantComplimentary(ServerRequest request) {
        return authenticatedUserResolver.resolve()
                .flatMap(administrator -> Mono.fromCallable(() -> QueryParameterParser.parseEventId(
                                request.pathVariable(QueryParameterParser.EVENT_ID)))
                        .zipWith(Mono.fromCallable(() -> HeaderParser.parseIdempotencyKey(
                                request.headers().firstHeader(HeaderParser.IDEMPOTENCY_KEY))))
                        .flatMap(target -> RequestHelper.bodyToMonoOrFailWhenEmpty(request,
                                        GrantComplimentaryRequestDto.class)
                                .flatMap(beanValidator::validateBeanOrThrowError)
                                .map(body -> orderMapper.toCommand(target.getT1(), administrator.id(),
                                        target.getT2(), body))))
                .flatMap(grantComplimentaryUseCase::grant)
                .map(orderMapper::toGrantResponse)
                .map(order -> mapManagement.buildResponse(order, SuccessMessage.CREATED))
                .flatMap(body -> ServerResponse.status(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body));
    }
}
