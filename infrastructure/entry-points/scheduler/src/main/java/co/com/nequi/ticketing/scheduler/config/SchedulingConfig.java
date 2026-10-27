package co.com.nequi.ticketing.scheduler.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled tasks only in the {@code worker} role.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class SchedulingConfig {
}
