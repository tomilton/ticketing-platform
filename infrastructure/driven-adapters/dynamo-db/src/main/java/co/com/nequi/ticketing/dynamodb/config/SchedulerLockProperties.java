package co.com.nequi.ticketing.dynamodb.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Table of the locks of the periodic tasks, read from the {@code adapters.dynamodb} block.
 *
 * @param schedulerLocksTable the table of locks, keyed by the name of the lock
 */
@Validated
@ConfigurationProperties("adapters.dynamodb")
public record SchedulerLockProperties(@NotBlank String schedulerLocksTable) {
}
