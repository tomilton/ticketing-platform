package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.EventPage;
import co.com.nequi.ticketing.model.event.request.EventPageQuery;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-006. The reference instant is far in the future, so the events written by other tests are
 * already past and never appear in this listing.
 */
class EventListingIntegrationTest {

    private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");
    private static final Integer PAGE_SIZE = 7;
    private static final Integer ACTIVE_FUTURE_EVENTS = 30;
    private static final Integer OTHER_EVENTS_PER_KIND = 5;
    private static final Integer WRITE_CONCURRENCY = 8;
    private static final Long DATE_SPREAD = 7L;

    private static DynamoDbIntegrationFixture fixture;
    private static EventDynamoRepository eventRepository;

    @BeforeAll
    static void setUp() {
        fixture = new DynamoDbIntegrationFixture();
        eventRepository = fixture.eventRepository();
    }

    @Test
    void shouldListEveryActiveFutureEventOnceInDateOrderWhenAllPagesAreRead() {
        // Given
        List<Instant> unorderedDates = IntStream.range(0, ACTIVE_FUTURE_EVENTS)
                .mapToObj(index -> NOW.plus(Duration.ofHours(index * DATE_SPREAD % ACTIVE_FUTURE_EVENTS + 1)))
                .toList();
        Mono<List<EventId>> activeFutureIds = Flux.fromIterable(unorderedDates)
                .flatMap(date -> fixture.putEvent(EventStatus.ACTIVE, date), WRITE_CONCURRENCY)
                .collectList();
        Mono<Void> otherEvents = Flux.range(1, OTHER_EVENTS_PER_KIND)
                .flatMap(day -> Flux.concat(
                        fixture.putEvent(EventStatus.ACTIVE, NOW.minus(Duration.ofDays(day))),
                        fixture.putEvent(EventStatus.CANCELLED, NOW.plus(Duration.ofDays(day))),
                        fixture.putEvent(EventStatus.FINISHED, NOW.plus(Duration.ofDays(day)))), WRITE_CONCURRENCY)
                .then();

        // When
        Mono<Tuple2<List<EventId>, List<EventPage>>> result = otherEvents.then(activeFutureIds)
                .zipWhen(ids -> readAllPages().collectList());

        // Then
        StepVerifier.create(result)
                .assertNext(idsAndPages -> {
                    List<EventPage> pages = idsAndPages.getT2();
                    List<Event> listed = pages.stream().flatMap(page -> page.items().stream()).toList();
                    assertThat(pages).extracting(page -> page.items().size()).containsExactly(7, 7, 7, 7, 2);
                    assertThat(listed).extracting(Event::getEventId)
                            .containsExactlyInAnyOrderElementsOf(idsAndPages.getT1());
                    assertThat(listed).extracting(Event::getDate).isSorted().doesNotHaveDuplicates();
                })
                .verifyComplete();
    }

    private static Flux<EventPage> readAllPages() {
        return eventRepository.findListable(NOW, new EventPageQuery(PAGE_SIZE, null))
                .expand(page -> Mono.justOrEmpty(page.nextCursor())
                        .flatMap(cursor -> eventRepository.findListable(NOW, new EventPageQuery(PAGE_SIZE, cursor))));
    }
}
