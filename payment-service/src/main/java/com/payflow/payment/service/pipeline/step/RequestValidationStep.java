package com.payflow.payment.service.pipeline.step;

import com.payflow.payment.controller.PaymentController.CreatePaymentRequest;
import com.payflow.payment.exception.PaymentException;
import com.payflow.payment.service.pipeline.PaymentCreationContext;
import com.payflow.payment.service.pipeline.PaymentCreationStep;
import org.springframework.stereotype.Component;

/**
 * Step 0: 请求校验
 * 在占用幂等键之前拒绝无效请求 — 否则校验失败或插入失败会让该键在 Redis 中卡住 24 小时。
 * 列长度上限与 sql/001_create_payment_order.sql 保持一致。
 */
@Component
public class RequestValidationStep implements PaymentCreationStep {

    /** 账户余额和账本只记 CNY,未做汇率换算之前不接受其他币种 */
    public static final String SUPPORTED_CURRENCY = "CNY";

    private static final int MAX_ACCOUNT_LENGTH = 32;
    private static final int MAX_PAYMENT_METHOD_LENGTH = 32;
    private static final int MAX_MEMO_LENGTH = 256;
    private static final int MAX_CALLBACK_URL_LENGTH = 512;

    @Override
    public void execute(PaymentCreationContext ctx) {
        validate(ctx.getRequest());
    }

    static void validate(CreatePaymentRequest req) {
        if (req == null) {
            throw invalid("Request body is required");
        }
        requireText(req.from_account(), "from_account", MAX_ACCOUNT_LENGTH);
        requireText(req.to_account(), "to_account", MAX_ACCOUNT_LENGTH);
        if (req.from_account().equals(req.to_account())) {
            throw invalid("from_account and to_account must differ");
        }
        if (req.amount() == null || req.amount() <= 0) {
            throw new PaymentException("INVALID_AMOUNT", "amount must be a positive number of cents");
        }
        if (req.currency() != null && !SUPPORTED_CURRENCY.equals(req.currency())) {
            throw invalid("Unsupported currency: " + req.currency() + " (only " + SUPPORTED_CURRENCY + ")");
        }
        requireText(req.payment_method(), "payment_method", MAX_PAYMENT_METHOD_LENGTH);
        maxLength(req.memo(), "memo", MAX_MEMO_LENGTH);
        maxLength(req.callback_url(), "callback_url", MAX_CALLBACK_URL_LENGTH);
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        maxLength(value, field, maxLength);
    }

    private static void maxLength(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw invalid(field + " must be at most " + maxLength + " characters");
        }
    }

    private static PaymentException invalid(String message) {
        return new PaymentException("INVALID_REQUEST", message);
    }
}
