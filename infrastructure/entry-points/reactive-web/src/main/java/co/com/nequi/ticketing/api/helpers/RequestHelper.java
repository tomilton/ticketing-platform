package co.com.nequi.ticketing.api.helpers;

import lombok.experimental.UtilityClass;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.EMPTY_REQUEST_BODY;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.INVALID_JSON;

/**
 * Reads request bodies. A body that cannot be read as JSON of the expected type is
 * {@code INVALID_JSON}; a missing body is {@code EMPTY_REQUEST_BODY}.
 */
@UtilityClass
public class RequestHelper {

    public static <T> Mono<T> bodyToMonoOrFailWhenEmpty(ServerRequest request, Class<T> type) {
        return request.bodyToMono(type)
                .onErrorMap(DecodingException.class, INVALID_JSON::build)
                .onErrorMap(ResponseStatusException.class, INVALID_JSON::build)
                .switchIfEmpty(Mono.defer(() -> Mono.error(EMPTY_REQUEST_BODY.build())));
    }
}
