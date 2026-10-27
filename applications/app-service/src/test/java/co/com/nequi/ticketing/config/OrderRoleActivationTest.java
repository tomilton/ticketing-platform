package co.com.nequi.ticketing.config;

import co.com.nequi.ticketing.api.config.RouteProperties;
import co.com.nequi.ticketing.api.order.OrderHandler;
import co.com.nequi.ticketing.api.order.OrderRouterRest;
import co.com.nequi.ticketing.scheduler.OrderRepublishScheduler;
import co.com.nequi.ticketing.sqs.commons.OrderPlacedMessageCodec;
import co.com.nequi.ticketing.sqs.listener.OrderPlacedListener;
import co.com.nequi.ticketing.sqs.listener.SqsErrorHandler;
import co.com.nequi.ticketing.sqs.listener.config.SqsListenerClientConfig;
import co.com.nequi.ticketing.sqs.listener.config.SqsListenerProperties;
import co.com.nequi.ticketing.usecase.processorder.ProcessOrderUseCase;
import co.com.nequi.ticketing.usecase.republishpendingorders.RepublishPendingOrdersUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OrderRoleActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OrderRouterRest.class, OrderPlacedListener.class, SqsErrorHandler.class,
                    OrderRepublishScheduler.class)
            .withBean(OrderHandler.class, () -> mock(OrderHandler.class))
            .withBean(RouteProperties.class, () -> new RouteProperties("/api/v1", "/events", "/events/{eventId}",
                    "/events/{eventId}/availability", "/events/availability", "/events/{eventId}/availability/stream",
                    "/orders", "/orders/{orderId}", "/events/{eventId}/complimentary"))
            .withBean(SqsListenerClientConfig.LISTENER_CLIENT, SqsAsyncClient.class, () -> mock(SqsAsyncClient.class))
            .withBean(SqsListenerProperties.class, () -> new SqsListenerProperties("us-east-1", null, "order-placed",
                    "order-placed-dlq", Duration.ofSeconds(1), 10, 1))
            .withBean(OrderPlacedMessageCodec.class, () -> new OrderPlacedMessageCodec(JsonMapper.builder().build()))
            .withBean(ProcessOrderUseCase.class, () -> mock(ProcessOrderUseCase.class))
            .withBean(RepublishPendingOrdersUseCase.class, () -> mock(RepublishPendingOrdersUseCase.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(ObservationRegistry.class, () -> ObservationRegistry.NOOP);

    @Test
    void shouldRegisterOrderRoutesOnlyWhenRoleIsApi() {
        // When / Then
        contextRunner.withPropertyValues("app.role=api")
                .run(context -> assertThat(context)
                        .hasSingleBean(OrderRouterRest.class)
                        .doesNotHaveBean(OrderPlacedListener.class)
                        .doesNotHaveBean(OrderRepublishScheduler.class));
    }

    @Test
    void shouldRegisterListenerAndSchedulerOnlyWhenRoleIsWorker() {
        // When / Then
        contextRunner.withPropertyValues("app.role=worker", "orders.republish-interval=1h")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(OrderRouterRest.class)
                        .hasSingleBean(OrderPlacedListener.class)
                        .hasSingleBean(OrderRepublishScheduler.class));
    }
}
