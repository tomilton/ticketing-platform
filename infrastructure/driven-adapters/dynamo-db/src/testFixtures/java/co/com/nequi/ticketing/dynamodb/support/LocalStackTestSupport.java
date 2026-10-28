package co.com.nequi.ticketing.dynamodb.support;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Shared LocalStack container for the integration tests. It runs the same init scripts as the
 * local environment, so the tables and queues are defined in a single place.
 */
public final class LocalStackTestSupport {

    public static final String EVENTS_TABLE = "events";
    public static final String INVENTORY_TABLE = "inventory";
    public static final String EVENTS_BY_STATUS_DATE_INDEX = "by-status-date";
    public static final String ORDERS_TABLE = "orders";
    public static final String ORDER_AUDIT_TABLE = "order_audit";
    public static final String IDEMPOTENCY_TABLE = "idempotency";
    public static final String CUSTOMER_LIMITS_TABLE = "customer_event_limits";
    public static final String EXPIRING_RESERVATIONS_INDEX = "expiring-reservations";
    public static final String PENDING_PUBLICATION_INDEX = "pending-publication";
    public static final String SCHEDULER_LOCKS_TABLE = "scheduler_locks";
    public static final String WEBHOOK_EVENTS_TABLE = "payment_webhook_events";
    public static final String ORDER_PLACED_QUEUE = "order-placed";
    public static final String ORDER_PLACED_DLQ = "order-placed-dlq";
    public static final String VISIBILITY_TIMEOUT_SECONDS = "5";
    public static final String MAX_RECEIVE_COUNT = "5";

    private static final String IMAGE = "localstack/localstack:4.14.0"
            + "@sha256:3ebc37595918b8accb852f8048fef2aff047d465167edd655528065b07bc364a";
    private static final String INIT_DIRECTORY = "deployment/localstack/init/ready.d/";
    private static final String CONTAINER_INIT_DIRECTORY = "/etc/localstack/init/ready.d/";
    private static final String DYNAMODB_SCRIPT = "01-dynamodb.sh";
    private static final String SQS_SCRIPT = "02-sqs.sh";
    private static final String PROJECT_ROOT_PROPERTY = "project.root";
    private static final String ACCESS_KEY_PROPERTY = "aws.accessKeyId";
    private static final String SECRET_KEY_PROPERTY = "aws.secretAccessKey";
    private static final int EXECUTABLE_MODE = 0755;
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    private static final LocalStackContainer CONTAINER = new LocalStackContainer(DockerImageName.parse(IMAGE)
            .asCompatibleSubstituteFor("localstack/localstack"))
            .withServices("dynamodb", "sqs")
            .withEnv(Map.ofEntries(
                    Map.entry("DYNAMODB_EVENTS_TABLE", EVENTS_TABLE),
                    Map.entry("DYNAMODB_INVENTORY_TABLE", INVENTORY_TABLE),
                    Map.entry("DYNAMODB_EVENTS_BY_STATUS_DATE_INDEX", EVENTS_BY_STATUS_DATE_INDEX),
                    Map.entry("DYNAMODB_ORDERS_TABLE", ORDERS_TABLE),
                    Map.entry("DYNAMODB_ORDER_AUDIT_TABLE", ORDER_AUDIT_TABLE),
                    Map.entry("DYNAMODB_IDEMPOTENCY_TABLE", IDEMPOTENCY_TABLE),
                    Map.entry("DYNAMODB_CUSTOMER_LIMITS_TABLE", CUSTOMER_LIMITS_TABLE),
                    Map.entry("DYNAMODB_EXPIRING_RESERVATIONS_INDEX", EXPIRING_RESERVATIONS_INDEX),
                    Map.entry("DYNAMODB_PENDING_PUBLICATION_INDEX", PENDING_PUBLICATION_INDEX),
                    Map.entry("DYNAMODB_SCHEDULER_LOCKS_TABLE", SCHEDULER_LOCKS_TABLE),
                    Map.entry("DYNAMODB_WEBHOOK_EVENTS_TABLE", WEBHOOK_EVENTS_TABLE),
                    Map.entry("SQS_ORDER_PLACED_QUEUE", ORDER_PLACED_QUEUE),
                    Map.entry("SQS_ORDER_PLACED_DLQ", ORDER_PLACED_DLQ),
                    Map.entry("SQS_VISIBILITY_TIMEOUT_SECONDS", VISIBILITY_TIMEOUT_SECONDS),
                    Map.entry("SQS_MAX_RECEIVE_COUNT", MAX_RECEIVE_COUNT)))
            .withCopyFileToContainer(script(DYNAMODB_SCRIPT), CONTAINER_INIT_DIRECTORY + DYNAMODB_SCRIPT)
            .withCopyFileToContainer(script(SQS_SCRIPT), CONTAINER_INIT_DIRECTORY + SQS_SCRIPT)
            .waitingFor(Wait.forLogMessage(".*\\[init\\] SQS queue " + ORDER_PLACED_QUEUE + " .*\\n", 1)
                    .withStartupTimeout(STARTUP_TIMEOUT));

    private LocalStackTestSupport() {
    }

    /**
     * Starts the container once per JVM and exposes its credentials to the default provider chain
     * of the AWS SDK, as the application reads them in every environment.
     */
    public static synchronized void start() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
            System.setProperty(ACCESS_KEY_PROPERTY, CONTAINER.getAccessKey());
            System.setProperty(SECRET_KEY_PROPERTY, CONTAINER.getSecretKey());
        }
    }

    public static DynamoDbAsyncClient asyncClient() {
        start();
        return DynamoDbAsyncClient.builder()
                .endpointOverride(CONTAINER.getEndpoint())
                .region(Region.of(CONTAINER.getRegion()))
                .credentialsProvider(credentials())
                .build();
    }

    public static SqsAsyncClient sqsAsyncClient() {
        start();
        return SqsAsyncClient.builder()
                .endpointOverride(CONTAINER.getEndpoint())
                .region(Region.of(CONTAINER.getRegion()))
                .credentialsProvider(credentials())
                .build();
    }

    public static DynamoDbProperties properties() {
        start();
        return new DynamoDbProperties(CONTAINER.getRegion(), CONTAINER.getEndpoint().toString(), EVENTS_TABLE,
                INVENTORY_TABLE, EVENTS_BY_STATUS_DATE_INDEX, Duration.ofSeconds(5), Duration.ofSeconds(2), 4,
                Duration.ofSeconds(2), ORDERS_TABLE, ORDER_AUDIT_TABLE, IDEMPOTENCY_TABLE, CUSTOMER_LIMITS_TABLE,
                EXPIRING_RESERVATIONS_INDEX, PENDING_PUBLICATION_INDEX, 10, Duration.ofSeconds(5));
    }

    /**
     * Spring properties that point the application to the container.
     *
     * @return the property names and values
     */
    public static Map<String, String> springProperties() {
        start();
        String region = CONTAINER.getRegion();
        String endpoint = CONTAINER.getEndpoint().toString();
        return Map.ofEntries(
                Map.entry("adapters.dynamodb.region", region),
                Map.entry("adapters.dynamodb.endpoint", endpoint),
                Map.entry("adapters.dynamodb.events-table", EVENTS_TABLE),
                Map.entry("adapters.dynamodb.inventory-table", INVENTORY_TABLE),
                Map.entry("adapters.dynamodb.events-by-status-date-index", EVENTS_BY_STATUS_DATE_INDEX),
                Map.entry("adapters.sqs.sender.region", region),
                Map.entry("adapters.sqs.sender.endpoint", endpoint),
                Map.entry("adapters.sqs.listener.region", region),
                Map.entry("adapters.sqs.listener.endpoint", endpoint));
    }

    private static StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(CONTAINER.getAccessKey(), CONTAINER.getSecretKey()));
    }

    private static MountableFile script(String name) {
        return MountableFile.forHostPath(Path.of(System.getProperty(PROJECT_ROOT_PROPERTY), INIT_DIRECTORY + name),
                EXECUTABLE_MODE);
    }
}
