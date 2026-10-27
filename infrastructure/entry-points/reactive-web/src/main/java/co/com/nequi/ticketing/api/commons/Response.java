package co.com.nequi.ticketing.api.commons;

/**
 * Envelope of every successful response.
 *
 * @param meta the success code and message
 * @param data the payload
 * @param <T>  the type of the payload
 */
public record Response<T>(Meta meta, T data) {
}
