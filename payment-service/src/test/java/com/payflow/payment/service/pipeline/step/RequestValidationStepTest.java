package com.payflow.payment.service.pipeline.step;

import com.payflow.payment.controller.PaymentController.CreatePaymentRequest;
import com.payflow.payment.exception.PaymentException;
import com.payflow.payment.service.pipeline.PaymentCreationContext;
import com.payflow.payment.service.pipeline.PaymentCreationPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RequestValidationStepTest {

    private final RequestValidationStep step = new RequestValidationStep();

    private static CreatePaymentRequest request(String from, String to, Long amount, String currency,
                                                String method, String memo, String callbackUrl) {
        return new CreatePaymentRequest(from, to, amount, currency, method, memo, callbackUrl);
    }

    private static CreatePaymentRequest valid() {
        return request("acc001", "acc002", 10_000L, "CNY", "BANK_TRANSFER", "goods", "https://merchant.example/hook");
    }

    private void run(CreatePaymentRequest req) {
        step.execute(new PaymentCreationContext("key-1", req));
    }

    @Test
    void acceptsValidRequest() {
        assertThatCode(() -> run(valid())).doesNotThrowAnyException();
    }

    @Test
    void acceptsMissingCurrencyAndOptionalFields() {
        assertThatCode(() -> run(request("acc001", "acc002", 1L, null, "BANK_TRANSFER", null, null)))
                .doesNotThrowAnyException();
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("zero amount", request("acc001", "acc002", 0L, "CNY", "BANK_TRANSFER", null, null), "INVALID_AMOUNT"),
                Arguments.of("negative amount", request("acc001", "acc002", -300_000L, "CNY", "BANK_TRANSFER", null, null), "INVALID_AMOUNT"),
                Arguments.of("missing amount", request("acc001", "acc002", null, "CNY", "BANK_TRANSFER", null, null), "INVALID_AMOUNT"),
                Arguments.of("unsupported currency", request("acc001", "acc002", 100L, "USD", "BANK_TRANSFER", null, null), "INVALID_REQUEST"),
                Arguments.of("same account", request("acc001", "acc001", 100L, "CNY", "BANK_TRANSFER", null, null), "INVALID_REQUEST"),
                Arguments.of("missing from_account", request(null, "acc002", 100L, "CNY", "BANK_TRANSFER", null, null), "INVALID_REQUEST"),
                Arguments.of("blank to_account", request("acc001", " ", 100L, "CNY", "BANK_TRANSFER", null, null), "INVALID_REQUEST"),
                Arguments.of("account id too long", request("a".repeat(33), "acc002", 100L, "CNY", "BANK_TRANSFER", null, null), "INVALID_REQUEST"),
                Arguments.of("missing payment_method", request("acc001", "acc002", 100L, "CNY", null, null, null), "INVALID_REQUEST"),
                Arguments.of("memo too long", request("acc001", "acc002", 100L, "CNY", "BANK_TRANSFER", "m".repeat(257), null), "INVALID_REQUEST"),
                Arguments.of("callback_url too long", request("acc001", "acc002", 100L, "CNY", "BANK_TRANSFER", null, "h".repeat(513)), "INVALID_REQUEST"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void rejectsInvalidRequest(String name, CreatePaymentRequest req, String errorCode) {
        assertThatThrownBy(() -> run(req))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(errorCode);
    }

    @Test
    void pipelineValidatesBeforeReservingTheIdempotencyKey() {
        IdempotencyStep idempotencyStep = mock(IdempotencyStep.class);
        OrderPersistStep orderPersistStep = mock(OrderPersistStep.class);
        PaymentCreationPipeline pipeline = new PaymentCreationPipeline(step, idempotencyStep,
                orderPersistStep, mock(RiskCheckStep.class), mock(EventPublishStep.class));

        CreatePaymentRequest negative = request("acc001", "acc002", -1L, "CNY", "BANK_TRANSFER", null, null);
        assertThatThrownBy(() -> pipeline.execute("key-1", negative)).isInstanceOf(PaymentException.class);

        verify(idempotencyStep, never()).execute(any());
        verify(orderPersistStep, never()).execute(any());
    }
}
