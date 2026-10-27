package co.com.nequi.ticketing.model.event;

import co.com.nequi.ticketing.model.event.request.EventPageQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventPageTest {

    @Test
    void shouldCopyItemsWhenSourceListChangesLater() {
        // Given
        List<Event> items = new ArrayList<>();

        // When
        EventPage page = new EventPage(items, null);
        items.add(null);

        // Then
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void shouldThrowNullPointerExceptionWhenQueryLimitIsNull() {
        // When / Then
        assertThatThrownBy(() -> new EventPageQuery(null, "cursor")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAllowMissingCursorWhenQueryIsTheFirstPage() {
        // When / Then
        assertThat(new EventPageQuery(20, null)).isEqualTo(new EventPageQuery(20, null));
    }
}
