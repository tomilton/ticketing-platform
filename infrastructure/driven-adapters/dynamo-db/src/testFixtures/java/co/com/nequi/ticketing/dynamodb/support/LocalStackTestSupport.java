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

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Shared LocalStack container for the integration tests. It runs the same init scripts as the
 * local environment, so the tables are defined in a single place.
 */
public final class LocalStackTestSupport {

    public static final String EVENTS_TABLE = "events";
    public static final String INVENTORY_TABLE = "inventory";
    public static final String EVENTS_BY_STATUS_DATE_INDEX = "by-status-date";

    private static final String IMAGE = "localstack/localstack:4.14.0"
            + "@sha256:3ebc37595918b8accb852f8048fef2aff047d465167edd655528065b07bc364a";
    private static final String INIT_SCRIPT = "deployment/localstack/init/ready.d/01-dynamodb.sh";
    private static final String CONTAINER_INIT_SCRIPT = "/etc/localstack/init/ready.d/01-dynamodb.sh";
    private static final String PROJECT_ROOT_PROPERTY = "project.root";
    private static final String ACCESS_KEY_PROPERTY = "aws.accessKeyId";
    private static final String SECRET_KEY_PROPERTY = "aws.secretAccessKey";
    private static final int EXECUTABLE_MODE = 0755;
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    private static final LocalStackContainer CONTAINER = new LocalStackContainer(DockerImageName.parse(IMAGE)
            .asCompatibleSubstituteFor("localstack/localstack"))
            .withServices("dynamodb")
            .withEnv(Map.of(
                    "DYNAMODB_EVENTS_TABLE", EVENTS_TABLE,
                    "DYNAMODB_INVENTORY_TABLE", INVENTORY_TABLE,
                    "DYNAMODB_EVENTS_BY_STATUS_DATE_INDEX", EVENTS_BY_STATUS_DATE_INDEX))
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of(System.getProperty(PROJECT_ROOT_PROPERTY), INIT_SCRIPT), EXECUTABLE_MODE),
                    CONTAINER_INIT_SCRIPT)
            .waitingFor(Wait.forLogMessage(".*\\[init\\] DynamoDB table " + INVENTORY_TABLE + " .*\\n", 1)
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
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(CONTAINER.getAccessKey(), CONTAINER.getSecretKey())))
                .build();
    }

    public static DynamoDbProperties properties() {
        start();
        return new DynamoDbProperties(CONTAINER.getRegion(), CONTAINER.getEndpoint().toString(), EVENTS_TABLE,
                INVENTORY_TABLE, EVENTS_BY_STATUS_DATE_INDEX, Duration.ofSeconds(5), Duration.ofSeconds(2), 4,
                Duration.ofSeconds(2),
                "orders", "order_audit", "idempotency", "customer_event_limits", "expiring-reservations",
                "pending-publication", 10, Duration.ofSeconds(5));
    }

    /**
     * Spring properties that point the application to the container.
     *
     * @return the property names and values
     */
    public static Map<String, String> springProperties() {
        start();
        return Map.of(
                "adapters.dynamodb.region", CONTAINER.getRegion(),
                "adapters.dynamodb.endpoint", CONTAINER.getEndpoint().toString(),
                "adapters.dynamodb.events-table", EVENTS_TABLE,
                "adapters.dynamodb.inventory-table", INVENTORY_TABLE,
                "adapters.dynamodb.events-by-status-date-index", EVENTS_BY_STATUS_DATE_INDEX);
    }
}
