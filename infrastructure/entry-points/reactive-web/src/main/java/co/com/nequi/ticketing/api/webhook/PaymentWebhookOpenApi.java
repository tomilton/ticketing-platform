package co.com.nequi.ticketing.api.webhook;

import co.com.nequi.ticketing.api.commons.ErrorEnvelope;
import co.com.nequi.ticketing.api.commons.Meta;
import co.com.nequi.ticketing.api.webhook.dto.PaymentNotificationRequestDto;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.experimental.UtilityClass;
import org.springdoc.core.fn.builders.operation.Builder;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

/**
 * OpenAPI documentation of the route of the payment gateway, equivalent to the feature contract.
 */
@UtilityClass
public class PaymentWebhookOpenApi {

    private static final String TAG = "Payment webhook";

    public static void receive(Builder operation) {
        operation.operationId("receivePaymentNotification")
                .tag(TAG)
                .summary("Receive a signed notice of the payment gateway with the result of a charge")
                .description("Public route without bearer token: X-Signature is the hexadecimal HMAC-SHA256 of "
                        + "X-Timestamp + \".\" + body with the agreed secret, and X-Timestamp must be within 5 minutes "
                        + "of now. The notice is queued for the worker; a copy of a notice already received answers "
                        + "200 without queuing it again")
                .parameter(parameterBuilder().name(PaymentNotificationHandler.TIMESTAMP_HEADER).in(ParameterIn.HEADER)
                        .required(true).description("Epoch seconds when the gateway signed the notice")
                        .implementation(Long.class))
                .parameter(parameterBuilder().name(PaymentNotificationHandler.SIGNATURE_HEADER).in(ParameterIn.HEADER)
                        .required(true).description("Hexadecimal HMAC-SHA256 of timestamp, dot and body")
                        .implementation(String.class))
                .requestBody(requestBodyBuilder().required(true).implementation(PaymentNotificationRequestDto.class))
                .response(responseBuilder().responseCode("200").description("Notice accepted (meta.code TKS0001)")
                        .implementation(MetaEnvelope.class))
                .response(error("400", "TKT0001 validation error, TKT0002 invalid JSON, TKT0003 empty body"))
                .response(error("401", "TKT0013 missing or invalid signature, or timestamp out of the window"))
                .response(error("413", "TKT0014 body larger than the maximum"))
                .response(error("500", "TKT0005, TKT0006, TKT0009 (the gateway sends the notice again)"));
    }

    private static org.springdoc.core.fn.builders.apiresponse.Builder error(String code, String description) {
        return responseBuilder().responseCode(code).description(description).implementation(ErrorEnvelope.class);
    }

    @Schema(name = "MetaEnvelope")
    record MetaEnvelope(Meta meta) {
    }
}
