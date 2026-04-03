package com.payflow.payment.workflow;

import java.io.Serializable;

public class PaymentWorkflowInput implements Serializable {

    private String paymentId;
    private String fromAccount;
    private String toAccount;
    private Long amount;
    private String callbackUrl;

    public PaymentWorkflowInput() {}

    public PaymentWorkflowInput(String paymentId, String fromAccount, String toAccount,
                                Long amount, String callbackUrl) {
        this.paymentId = paymentId;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.callbackUrl = callbackUrl;
    }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public String getFromAccount() { return fromAccount; }
    public void setFromAccount(String fromAccount) { this.fromAccount = fromAccount; }
    public String getToAccount() { return toAccount; }
    public void setToAccount(String toAccount) { this.toAccount = toAccount; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
}
