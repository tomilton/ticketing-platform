package co.com.nequi.ticketing.api.handle.exceptions;

import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.exception.BusinessException;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webflux.autoconfigure.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * The only place where an error becomes an HTTP response: {@code {"errors": [...]}} with a stable
 * catalog code and the path of the request.
 */
@Component
@Order(-2)
public class GlobalErrorWebExceptionHandler extends AbstractErrorWebExceptionHandler {

    private static final String ERRORS = "errors";

    private final ErrorResponseBuilder errorResponseBuilder;

    public GlobalErrorWebExceptionHandler(ErrorAttributes errorAttributes, ApplicationContext applicationContext,
                                          ServerCodecConfigurer serverCodecConfigurer,
                                          ErrorResponseBuilder errorResponseBuilder) {
        super(errorAttributes, new WebProperties.Resources(), applicationContext);
        this.errorResponseBuilder = errorResponseBuilder;
        setMessageWriters(serverCodecConfigurer.getWriters());
        setMessageReaders(serverCodecConfigurer.getReaders());
    }

    @Override
    protected RouterFunction<ServerResponse> getRoutingFunction(ErrorAttributes errorAttributes) {
        return RouterFunctions.route(RequestPredicates.all(), this::renderErrorResponse);
    }

    @Override
    protected void logError(ServerRequest request, ServerResponse response, Throwable throwable) {
        // ErrorResponseBuilder already logs every error with its catalog code.
    }

    private Mono<ServerResponse> renderErrorResponse(ServerRequest request) {
        return Mono.defer(() -> Mono.<ErrorResult>error(getError(request)))
                .onErrorResume(NoResourceFoundException.class, errorResponseBuilder::buildErrorResponse)
                .onErrorResume(TechnicalException.class, errorResponseBuilder::buildErrorResponse)
                .onErrorResume(ValidationException.class, errorResponseBuilder::buildErrorResponse)
                .onErrorResume(BusinessException.class, errorResponseBuilder::buildErrorResponse)
                .onErrorResume(errorResponseBuilder::buildErrorResponse)
                .flatMap(result -> ServerResponse.status(result.status())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(ERRORS, List.of(result.response().withDomain(request.path())))));
    }
}
