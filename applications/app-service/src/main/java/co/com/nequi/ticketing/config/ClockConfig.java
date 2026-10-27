package co.com.nequi.ticketing.config;

import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Source of the current time of the domain, in UTC.
 */
@Configuration
public class ClockConfig {

    @Bean
    public ClockProvider clockProvider() {
        return Clock.systemUTC()::instant;
    }
}
