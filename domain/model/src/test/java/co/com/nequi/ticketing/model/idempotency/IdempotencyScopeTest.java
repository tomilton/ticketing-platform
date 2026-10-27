package co.com.nequi.ticketing.model.idempotency;

import co.com.nequi.ticketing.model.shared.CustomerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyScopeTest {

    private static final UUID ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void shouldUseCustomerUuidWhenScopeIsOfCustomer() {
        // When / Then
        assertThat(IdempotencyScope.ofCustomer(new CustomerId(ID)).value())
                .isEqualTo("22222222-2222-4222-8222-222222222222");
    }

    @Test
    void shouldPrefixAdministratorUuidWhenScopeIsOfAdministrator() {
        // When / Then
        assertThat(IdempotencyScope.ofAdministrator(new CustomerId(ID)).value())
                .isEqualTo("ADMIN#22222222-2222-4222-8222-222222222222");
    }

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new IdempotencyScope(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void shouldThrowIllegalArgumentExceptionWhenValueIsBlank(String value) {
        // When / Then
        assertThatThrownBy(() -> new IdempotencyScope(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
