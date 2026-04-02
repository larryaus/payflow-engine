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

        // 记录每个步骤是否已成功执行，用于决定需要执行哪些补偿
        boolean frozen = false;
        boolean ledgerCreated = false;
        boolean transferred = false;

        try {
            // 1. 标记处理中
            activities.markProcessing(paymentId);

            // 2. 冻结付款方金额
            activities.freezeAmount(input.getFromAccount(), input.getAmount());
            frozen = true;

            // 3. 创建复式记账分录
            activities.createLedgerEntry(
                    paymentId,
                    input.getFromAccount(),
                    input.getToAccount(),
                    input.getAmount()
            );
            ledgerCreated = true;

            // 4. 解冻并执行转账
            activities.transfer(
                    input.getFromAccount(),
                    input.getToAccount(),
                    input.getAmount()
            );
            transferred = true;

            // 5. 标记完成
            activities.markCompleted(paymentId);

            // 6. 发送完成通知
            activities.sendCompletedNotification(paymentId);

            log.info("Payment workflow completed for {}", paymentId);

        } catch (Exception e) {
            log.error("Payment workflow failed for {}: {}, compensating (transferred={}, ledgerCreated={}, frozen={})",
                    paymentId, e.getMessage(), transferred, ledgerCreated, frozen);

            // 补偿按成功步骤逆序执行
            if (transferred) {
                activities.reverseTransfer(
                        input.getFromAccount(),
                        input.getToAccount(),
                        input.getAmount()
                );
            }
            if (ledgerCreated) {
                activities.reverseLedgerEntry(paymentId);
            }
            if (frozen) {
                activities.unfreezeAmount(input.getFromAccount(), input.getAmount());
            }

            activities.markFailed(paymentId, e.getMessage());
            activities.sendFailedNotification(paymentId, e.getMessage());
        }
    }
}
