package co.com.nequi.ticketing.api.helpers;

import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import lombok.experimental.UtilityClass;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.exc.MismatchedInputException;

import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.EMPTY_REQUEST_BODY;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.INVALID_JSON;

/**
 * Reads request bodies. A number that does not fit an integer field is a validation error that names
 * the field; any other body that cannot be read as JSON of the expected type is {@code INVALID_JSON};
 * a missing body is {@code EMPTY_REQUEST_BODY}.
 */
@UtilityClass
public class RequestHelper {

    private static final Set<Class<?>> INTEGER_TYPES = Set.of(Integer.class, Long.class, Short.class,
            BigInteger.class, int.class, long.class, short.class);
    private static final String INTEGER_RULE = "must be an integer";

    public static <T> Mono<T> bodyToMonoOrFailWhenEmpty(ServerRequest request, Class<T> type) {
        return request.bodyToMono(type)
                .onErrorMap(DecodingException.class, RequestHelper::toReadError)
                .onErrorMap(ResponseStatusException.class, RequestHelper::toReadError)
                .switchIfEmpty(Mono.defer(() -> Mono.error(EMPTY_REQUEST_BODY.build())));
    }

    private static RuntimeException toReadError(Throwable error) {
        return integerFieldOf(error)
                .<RuntimeException>map(field -> new ValidationException(field, INTEGER_RULE))
                .orElseGet(() -> INVALID_JSON.build(error));
    }

    private static Optional<String> integerFieldOf(Throwable error) {
        return Stream.iterate(error, cause -> cause != null, Throwable::getCause)
                .filter(MismatchedInputException.class::isInstance)
                .map(MismatchedInputException.class::cast)
                .filter(mismatch -> INTEGER_TYPES.contains(mismatch.getTargetType()) && !mismatch.getPath().isEmpty())
                .map(mismatch -> mismatch.getPath().getLast().getPropertyName())
                .findFirst();
    }
}
