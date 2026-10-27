package com.payflow.payment.service.pipeline.step;

import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.service.pipeline.PaymentCreationContext;
import com.payflow.payment.service.pipeline.PaymentCreationStep;
import com.payflow.payment.workflow.PaymentWorkflow;
import com.payflow.payment.workflow.PaymentWorkflowInput;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Step 4: 启动 Temporal Workflow
 * 异步执行 冻结 → 记账 → 转账 → 通知，失败时由 Saga 逆序补偿
 * Workflow ID 固定为 payment-{paymentId}，保证同一订单只启动一次
 */
@Component
public class WorkflowStartStep implements PaymentCreationStep {

    private final WorkflowClient workflowClient;

    @Value("${temporal.task-queue}")
    private String taskQueue;

    public WorkflowStartStep(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @Override
    public void execute(PaymentCreationContext ctx) {
        PaymentOrder order = ctx.getOrder();
        PaymentWorkflowInput input = new PaymentWorkflowInput(
                order.getPaymentId(),
                order.getFromAccount(),
                order.getToAccount(),
                order.getAmount(),
                order.getCallbackUrl()
        );
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId("payment-" + order.getPaymentId())
                .setTaskQueue(taskQueue)
                .build();
        PaymentWorkflow workflow = workflowClient.newWorkflowStub(PaymentWorkflow.class, options);
        WorkflowClient.start(workflow::processPayment, input);
    }
}
