package co.com.nequi.ticketing.model.shared;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserTest {

    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());

    @Test
    void shouldKeepIdentityAndRoleWhenComponentsAreValid() {
        // When
        AuthenticatedUser user = new AuthenticatedUser(CUSTOMER_ID, Boolean.TRUE);

        // Then
        assertThat(user.id()).isEqualTo(CUSTOMER_ID);
        assertThat(user.admin()).isTrue();
    }

    @Test
    void shouldThrowNullPointerExceptionWhenComponentIsNull() {
        // When / Then
        assertThatThrownBy(() -> new AuthenticatedUser(null, Boolean.FALSE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedUser(CUSTOMER_ID, null)).isInstanceOf(NullPointerException.class);
    }
}
