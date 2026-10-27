package co.com.nequi.ticketing.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * Page sizes of the event listing, read from the {@code pagination.events} block.
 *
 * @param defaultSize the size used when the request has no {@code limit}
 * @param maxSize     the largest accepted {@code limit}, at most {@value #MAX_ALLOWED_SIZE}
 */
@ConfigurationProperties("pagination.events")
public record EventPaginationProperties(Integer defaultSize, Integer maxSize) {

    private static final int MIN_SIZE = 1;
    private static final int MAX_ALLOWED_SIZE = 100;

    public EventPaginationProperties {
        Objects.requireNonNull(defaultSize, "Default page size must not be null");
        Objects.requireNonNull(maxSize, "Maximum page size must not be null");
        if (defaultSize < MIN_SIZE || defaultSize > maxSize || maxSize > MAX_ALLOWED_SIZE) {
            throw new IllegalArgumentException("Page sizes must satisfy 1 <= default <= max <= " + MAX_ALLOWED_SIZE);
        }
    }
}
