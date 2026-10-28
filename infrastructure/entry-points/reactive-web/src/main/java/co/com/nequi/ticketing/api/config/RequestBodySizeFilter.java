package co.com.nequi.ticketing.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.PAYLOAD_TOO_LARGE;

/**
 * Rejects a request that declares a body larger than the maximum before reading it. A body without
 * a declared length is limited while it is decoded, with the same maximum.
 */
@Component
public class RequestBodySizeFilter implements WebFilter {

    private final Long maximumBytes;

    public RequestBodySizeFilter(@Value("${spring.codec.max-in-memory-size}") DataSize maximumSize) {
        this.maximumBytes = maximumSize.toBytes();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.just(exchange.getRequest().getHeaders().getContentLength())
                .filter(length -> length > maximumBytes)
                .flatMap(length -> Mono.<Void>error(PAYLOAD_TOO_LARGE.build()))
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
    }
}
