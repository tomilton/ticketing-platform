package co.com.nequi.ticketing.api.validation;

import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class BeanValidatorTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    private final BeanValidator beanValidator = new BeanValidator(FACTORY.getValidator());

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void shouldEmitBeanWhenItHasNoViolations() {
        // Given
        SampleDto bean = new SampleDto("Rock", 1);

        // When / Then
        StepVerifier.create(beanValidator.validateBeanOrThrowError(bean))
                .expectNext(bean)
                .verifyComplete();
    }

    @Test
    void shouldFailNamingFirstFieldWhenBeanHasViolations() {
        // When / Then
        StepVerifier.create(beanValidator.validateBeanOrThrowError(new SampleDto(" ", 0)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ValidationException.class);
                    assertThat(((ValidationException) error).getField()).isEqualTo("capacity");
                })
                .verify();
    }

    record SampleDto(@NotBlank String name, @Min(1) Integer capacity) {
    }
}
