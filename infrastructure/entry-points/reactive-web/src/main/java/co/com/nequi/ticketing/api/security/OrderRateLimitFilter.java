package co.com.nequi.ticketing.api.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.RATE_LIMITED;

/**
 * Limits the purchases each customer may attempt in this replica, after the token is verified and
 * before the purchase reaches the idempotency or the inventory. The limit across replicas belongs to
 * the WAF and the throttling of API Gateway. A limiter idle for ten minutes is forgotten.
 */
public class OrderRateLimitFilter implements WebFilter {

    private static final Duration IDLE_EXPIRATION = Duration.ofMinutes(10);
    private static final Long MAXIMUM_CUSTOMERS = 100_000L;
    private static final Long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();
    private static final Long MINIMUM_RETRY_AFTER_SECONDS = 1L;
    private static final String LIMITER_NAME = "orders-";

    private final AuthenticatedUserResolver authenticatedUserResolver;
    private final String ordersPath;
    private final RateLimiterConfig config;
    private final Cache<UUID, AtomicRateLimiter> limiters;

    public OrderRateLimitFilter(AuthenticatedUserResolver authenticatedUserResolver, String ordersPath,
                                RateLimitProperties properties) {
        this.authenticatedUserResolver = authenticatedUserResolver;
        this.ordersPath = ordersPath;
        this.config = RateLimiterConfig.custom()
                .limitForPeriod(properties.limitForPeriod())
                .limitRefreshPeriod(properties.period())
                .timeoutDuration(Duration.ZERO)
                .build();
        this.limiters = Caffeine.newBuilder()
                .expireAfterAccess(IDLE_EXPIRATION)
                .maximumSize(MAXIMUM_CUSTOMERS)
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.just(exchange.getRequest())
                .filter(this::isPurchase)
                .flatMap(request -> authenticatedUserResolver.resolve())
                .map(user -> limiters.get(user.id().value(), this::newLimiter))
                .filter(limiter -> !limiter.acquirePermission())
                .flatMap(limiter -> reject(exchange, limiter))
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
    }

    private Boolean isPurchase(ServerHttpRequest request) {
        return HttpMethod.POST.equals(request.getMethod())
                && ordersPath.equals(request.getPath().pathWithinApplication().value());
    }

    private AtomicRateLimiter newLimiter(UUID customer) {
        return new AtomicRateLimiter(LIMITER_NAME + customer, config);
    }

    private static Mono<Void> reject(ServerWebExchange exchange, AtomicRateLimiter limiter) {
        return Mono.fromRunnable(() -> exchange.getResponse().getHeaders()
                        .set(HttpHeaders.RETRY_AFTER, String.valueOf(secondsToWait(limiter))))
                .then(Mono.defer(() -> Mono.error(RATE_LIMITED.build())));
    }

    private static Long secondsToWait(AtomicRateLimiter limiter) {
        long nanosToWait = limiter.getDetailedMetrics().getNanosToWait();
        return Math.max(MINIMUM_RETRY_AFTER_SECONDS, (nanosToWait + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
    }
}
