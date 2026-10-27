package co.com.nequi.ticketing.api.security;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Purchases a customer may attempt in one replica, read from the {@code rate-limit.orders} block.
 *
 * @param limitForPeriod the purchases allowed in each period
 * @param period         the period after which the purchases allowed are renewed
 */
@Validated
@ConfigurationProperties("rate-limit.orders")
public record RateLimitProperties(@NotNull @Positive Integer limitForPeriod,
                                  @NotNull Duration period) {
}
