package co.com.nequi.ticketing.paymentsimulator;

import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * The payment gateway the use cases receive: the simulator behind its circuit breaker.
 */
@Configuration
public class PaymentGatewayConfig {

    @Bean
    @Primary
    PaymentGateway resilientPaymentGateway(PaymentSimulatorGateway simulator, PaymentCircuitBreakerProperties properties,
                                           MeterRegistry meterRegistry) {
        return new ResilientPaymentGateway(simulator, properties, meterRegistry, Clock.systemUTC());
    }
}
