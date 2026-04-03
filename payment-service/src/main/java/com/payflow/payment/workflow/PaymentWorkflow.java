package com.payflow.payment.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface PaymentWorkflow {

    @WorkflowMethod
    void processPayment(PaymentWorkflowInput input);
}
