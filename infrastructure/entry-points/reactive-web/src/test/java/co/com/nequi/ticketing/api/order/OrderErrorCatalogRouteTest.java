package co.com.nequi.ticketing.api.order;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.order.request.GetOrderQuery;
import co.com.nequi.ticketing.usecase.getorder.GetOrderUseCase;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SC-007: every error case of the order specification answers with its HTTP status and an exact
 * catalog code. It completes the catalog test of the event routes, which has no identity headers.
 */
@WebFluxTest
@ContextConfiguration(classes = OrderRouteIntegrationTest.OrderRouteTestConfig.class)
class OrderErrorCatalogRouteTest {

    private static final String ORDERS = "/api/v1/orders";
    private static final String CUSTOMER = "11111111-1111-4111-8111-111111111111";
    private static final String KEY = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
    private static final String KNOWN_ID = "3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f";
    private static final String CODE_PATTERN = "^TK[BT]\\d{4}$";
    private static final String BODY = "{\"eventId\":\"" + KNOWN_ID + "\",\"quantity\":2}";
    private static final Consumer<OrderErrorCatalogRouteTest> NO_STUB = test -> {
    };

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private PlaceOrderUseCase placeOrderUseCase;

    @MockitoBean
    private GetOrderUseCase getOrderUseCase;

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorCases")
    void shouldAnswerCatalogCodeWhenSpecificationErrorHappens(String scenario, HttpMethod method, String uri,
                                                              String customer, String key, String body,
                                                              Integer status, String code,
                                                              Consumer<OrderErrorCatalogRouteTest> stub) {
        // Given
        stub.accept(this);
        WebTestClient.RequestBodySpec request = webTestClient.method(method).uri(uri)
                .contentType(MediaType.APPLICATION_JSON);
        request.headers(headers -> {
            headers.set("X-Customer-Id", customer);
            headers.set("Idempotency-Key", key);
        });

        // When
        WebTestClient.ResponseSpec response = body == null ? request.exchange() : request.bodyValue(body).exchange();

        // Then
        response.expectStatus().isEqualTo(status)
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo(code)
                .jsonPath("$.errors[0].code").value(value -> assertThat((String) value).matches(CODE_PATTERN));
    }

    private static Stream<Arguments> errorCases() {
        return Stream.of(
                Arguments.of("US1-2 sold out", HttpMethod.POST, ORDERS, CUSTOMER, KEY, BODY, 409, "TKB0003",
                        rejection(BusinessErrorMessage.TICKETS_SOLD_OUT)),
                Arguments.of("US1-3 event not found", HttpMethod.POST, ORDERS, CUSTOMER, KEY, BODY, 404, "TKB0001",
                        rejection(BusinessErrorMessage.EVENT_NOT_FOUND)),
                Arguments.of("US1-4 event not active", HttpMethod.POST, ORDERS, CUSTOMER, KEY, BODY, 409, "TKB0007",
                        rejection(BusinessErrorMessage.EVENT_NOT_ACTIVE)),
                Arguments.of("US1-5 quantity seven", HttpMethod.POST, ORDERS, CUSTOMER, KEY,
                        BODY.replace("\"quantity\":2", "\"quantity\":7"), 400, "TKT0001", NO_STUB),
                Arguments.of("US1-6 limit exceeded", HttpMethod.POST, ORDERS, CUSTOMER, KEY, BODY, 422, "TKB0004",
                        rejection(BusinessErrorMessage.TICKET_LIMIT_EXCEEDED)),
                Arguments.of("US1-7 missing customer", HttpMethod.POST, ORDERS, "", KEY, BODY, 400, "TKT0001",
                        NO_STUB),
                Arguments.of("US2-5 order of another customer", HttpMethod.GET, ORDERS + "/" + KNOWN_ID, CUSTOMER,
                        KEY, null, 404, "TKB0002",
                        (Consumer<OrderErrorCatalogRouteTest>) test -> when(test.getOrderUseCase.get(any(GetOrderQuery.class)))
                                .thenReturn(Mono.error(BusinessErrorMessage.ORDER_NOT_FOUND.build()))),
                Arguments.of("US2-6 invalid order id", HttpMethod.GET, ORDERS + "/no-es-uuid", CUSTOMER, KEY, null,
                        400, "TKT0001", NO_STUB),
                Arguments.of("US4-2 key reused", HttpMethod.POST, ORDERS, CUSTOMER, KEY, BODY, 422, "TKB0005",
                        rejection(BusinessErrorMessage.IDEMPOTENCY_KEY_REUSED)),
                Arguments.of("US4-3 request in progress", HttpMethod.POST, ORDERS, CUSTOMER, KEY, BODY, 409,
                        "TKB0006", rejection(BusinessErrorMessage.REQUEST_IN_PROGRESS)),
                Arguments.of("US4-4 invalid key", HttpMethod.POST, ORDERS, CUSTOMER, "abc", BODY, 400, "TKT0004",
                        NO_STUB));
    }

    private static Consumer<OrderErrorCatalogRouteTest> rejection(BusinessErrorMessage error) {
        return test -> when(test.placeOrderUseCase.place(any(PlaceOrderCommand.class))).thenReturn(Mono.error(error.build()));
    }
}
