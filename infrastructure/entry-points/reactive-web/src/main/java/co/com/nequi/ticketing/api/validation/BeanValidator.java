package co.com.nequi.ticketing.api.validation;

import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;

/**
 * Bean Validation of request bodies. The first violation, in field order, becomes a
 * {@link ValidationException} that names the field.
 */
@Component
@RequiredArgsConstructor
public class BeanValidator {

    private final Validator validator;

    public <T> Mono<T> validateBeanOrThrowError(T bean) {
        return Mono.fromCallable(() -> validator.validate(bean))
                .flatMapMany(Flux::fromIterable)
                .sort(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .next()
                .flatMap(violation -> Mono.<T>error(toValidationException(violation)))
                .switchIfEmpty(Mono.just(bean));
    }

    private static <T> ValidationException toValidationException(ConstraintViolation<T> violation) {
        return new ValidationException(violation.getPropertyPath().toString(), violation.getMessage());
    }
}
