package co.com.nequi.ticketing.api.helpers;

import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import lombok.experimental.UtilityClass;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.exc.MismatchedInputException;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.EMPTY_REQUEST_BODY;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.INVALID_JSON;

/**
 * Reads request bodies. A number that does not fit an integer field, or a text that is not a UUID
 * in a UUID field, is a validation error that names the field; any other body that cannot be read as JSON of the expected type is {@code INVALID_JSON};
 * a missing body is {@code EMPTY_REQUEST_BODY}.
 */
@UtilityClass
public class RequestHelper {

    private static final Set<Class<?>> INTEGER_TYPES = Set.of(Integer.class, Long.class, Short.class,
            BigInteger.class, int.class, long.class, short.class);
    private static final String INTEGER_RULE = "must be an integer";
    private static final String UUID_RULE = "must be a UUID";

    public static <T> Mono<T> bodyToMonoOrFailWhenEmpty(ServerRequest request, Class<T> type) {
        return request.bodyToMono(type)
                .onErrorMap(DecodingException.class, RequestHelper::toReadError)
                .onErrorMap(ResponseStatusException.class, RequestHelper::toReadError)
                .switchIfEmpty(Mono.defer(() -> Mono.error(EMPTY_REQUEST_BODY.build())));
    }

    private static RuntimeException toReadError(Throwable error) {
        return typedFieldOf(error)
                .<RuntimeException>map(field -> new ValidationException(field.getKey(), field.getValue()))
                .orElseGet(() -> INVALID_JSON.build(error));
    }

    private static Optional<Map.Entry<String, String>> typedFieldOf(Throwable error) {
        return Stream.iterate(error, cause -> cause != null, Throwable::getCause)
                .filter(MismatchedInputException.class::isInstance)
                .map(MismatchedInputException.class::cast)
                .filter(mismatch -> !mismatch.getPath().isEmpty())
                .flatMap(mismatch -> ruleOf(mismatch.getTargetType())
                        .map(rule -> Map.entry(mismatch.getPath().getLast().getPropertyName(), rule)).stream())
                .findFirst();
    }

    private static Optional<String> ruleOf(Class<?> targetType) {
        if (INTEGER_TYPES.contains(targetType)) {
            return Optional.of(INTEGER_RULE);
        }
        return UUID.class.equals(targetType) ? Optional.of(UUID_RULE) : Optional.empty();
    }
}
