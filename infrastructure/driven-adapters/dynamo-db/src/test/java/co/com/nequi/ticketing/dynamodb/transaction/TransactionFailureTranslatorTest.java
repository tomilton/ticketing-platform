package co.com.nequi.ticketing.dynamodb.transaction;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionFailureTranslatorTest {

    private final TransactionFailureTranslator translator = new TransactionFailureTranslator();

    @ParameterizedTest
    @CsvSource({
            "ConditionalCheckFailed, ConditionalCheckFailed, ConditionalCheckFailed, None, None, ConditionalCheckFailed, EVENT_NOT_ACTIVE",
            "None, ConditionalCheckFailed, ConditionalCheckFailed, None, None, None, TICKET_LIMIT_EXCEEDED",
            "None, None, ConditionalCheckFailed, None, None, ConditionalCheckFailed, TICKET_LIMIT_EXCEEDED",
            "None, None, None, None, None, ConditionalCheckFailed, REQUEST_IN_PROGRESS",
            "None, ConditionalCheckFailed, None, None, None, ConditionalCheckFailed, REQUEST_IN_PROGRESS"})
    void shouldReturnBusinessErrorByPriorityWhenConditionsFail(String event, String shard, String limit, String order,
                                                               String audit, String key, BusinessErrorMessage expected) {
        // When
        RuntimeException error = translator.translateReservation(cancellation(event, shard, limit, order, audit, key));

        // Then
        assertThat(error).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                .isEqualTo(expected);
    }

    @Test
    void shouldSignalShardSoldOutWhenOnlyShardConditionFails() {
        // When / Then
        assertThat(translator.translateReservation(
                cancellation("None", "ConditionalCheckFailed", "None", "None", "None", "None")))
                .isInstanceOf(ShardSoldOutException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "None, None, None, ConditionalCheckFailed, None, None",
            "None, None, None, None, ConditionalCheckFailed, None",
            "None, None, None, None, None, ValidationError"})
    void shouldReturnDynamoDbErrorWhenOrderOrAuditFailsOrCodeIsUnknown(String event, String shard, String limit,
                                                                      String order, String audit, String key) {
        // When
        RuntimeException error = translator.translateReservation(cancellation(event, shard, limit, order, audit, key));

        // Then
        assertThat(error).isInstanceOf(TechnicalException.class)
                .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR);
    }

    @Test
    void shouldSignalRetryableConflictWhenTransactionConflicts() {
        // When / Then
        assertThat(translator.translateReservation(
                cancellation("None", "TransactionConflict", "None", "None", "None", "None")))
                .isInstanceOf(TransactionConflictException.class)
                .hasCauseInstanceOf(TransactionCanceledException.class);
    }

    @Test
    void shouldReturnDynamoDbErrorWhenCancellationHasNoReasons() {
        // When
        RuntimeException error = translator.translateReservation(TransactionCanceledException.builder().build());

        // Then
        assertThat(error).isInstanceOf(TechnicalException.class);
    }

    static TransactionCanceledException cancellation(String... codes) {
        return TransactionCanceledException.builder()
                .cancellationReasons(Arrays.stream(codes)
                        .map(code -> CancellationReason.builder().code(code).build())
                        .toList())
                .build();
    }
}
