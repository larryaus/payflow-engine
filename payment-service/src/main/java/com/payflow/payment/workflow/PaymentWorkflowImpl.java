package com.payflow.payment.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

public class PaymentWorkflowImpl implements PaymentWorkflow {

    private static final Logger log = Workflow.getLogger(PaymentWorkflowImpl.class);

    private final PaymentActivities activities = Workflow.newActivityStub(
            PaymentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .build())
                    .build()
    );

    @Override
    public void processPayment(PaymentWorkflowInput input) {
        String paymentId = input.getPaymentId();

        // 1. 标记处理中
        activities.markProcessing(paymentId);

        // 2. 冻结付款方金额
        activities.freezeAmount(input.getFromAccount(), input.getAmount());

        try {
            // 3. 创建复式记账分录
            activities.createLedgerEntry(
                    paymentId,
                    input.getFromAccount(),
                    input.getToAccount(),
                    input.getAmount()
            );

            // 4. 解冻并执行转账
            activities.transfer(
                    input.getFromAccount(),
                    input.getToAccount(),
                    input.getAmount()
            );

            // 5. 标记完成
            activities.markCompleted(paymentId);

            // 6. 发送完成通知（Kafka → notification-service）
            activities.sendCompletedNotification(paymentId);

            log.info("Payment workflow completed for {}", paymentId);

        } catch (Exception e) {
            log.error("Payment workflow failed for {}: {}", paymentId, e.getMessage());

            // 补偿：解冻已冻结金额
            activities.unfreezeAmount(input.getFromAccount(), input.getAmount());

            // 标记失败并通知
            activities.markFailed(paymentId, e.getMessage());
            activities.sendFailedNotification(paymentId, e.getMessage());
        }
    }
}
