package com.payflow.payment.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Saga;
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

        Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());

        try {
            // 1. 标记处理中
            activities.markProcessing(paymentId);

            // 2. 冻结付款方金额
            activities.freezeAmount(input.getFromAccount(), input.getAmount());
            saga.addCompensation(() -> activities.unfreezeAmount(input.getFromAccount(), input.getAmount()));

            // 3. 创建复式记账分录
            activities.createLedgerEntry(
                    paymentId,
                    input.getFromAccount(),
                    input.getToAccount(),
                    input.getAmount()
            );
            saga.addCompensation(() -> activities.reverseLedgerEntry(paymentId));

            // 4. 解冻并执行转账
            activities.transfer(
                    input.getFromAccount(),
                    input.getToAccount(),
                    input.getAmount()
            );
            saga.addCompensation(() -> activities.reverseTransfer(
                    input.getFromAccount(),
                    input.getToAccount(),
                    input.getAmount()
            ));

            // 5. 标记完成
            activities.markCompleted(paymentId);

            // 6. 发送完成通知
            activities.sendCompletedNotification(paymentId);

            log.info("Payment workflow completed for {}", paymentId);

        } catch (Exception e) {
            log.error("Payment workflow failed for {}: {}, starting compensation", paymentId, e.getMessage());
            saga.compensate();
            activities.markFailed(paymentId, e.getMessage());
            activities.sendFailedNotification(paymentId, e.getMessage());
        }
    }
}
