package co.com.nequi.ticketing.api.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Paths of the API, read from the {@code routes} block. Every path is relative to the base path.
 *
 * @param basePath           the common prefix of every route
 * @param events             the collection of events
 * @param eventById          one event
 * @param eventAvailability  the availability of one event
 * @param batchAvailability  the availability of several events
 * @param availabilityStream the live availability of one event
 */
@Validated
@ConfigurationProperties("routes")
public record RouteProperties(@NotBlank String basePath,
                              @NotBlank String events,
                              @NotBlank String eventById,
                              @NotBlank String eventAvailability,
                              @NotBlank String batchAvailability,
                              @NotBlank String availabilityStream) {

    public String eventsPath() {
        return basePath + events;
    }

    public String eventByIdPath() {
        return basePath + eventById;
    }

    public String eventAvailabilityPath() {
        return basePath + eventAvailability;
    }

    public String batchAvailabilityPath() {
        return basePath + batchAvailability;
    }

    public String availabilityStreamPath() {
        return basePath + availabilityStream;
    }
}
