package com.payflow.payment.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PaymentActivities {

    @ActivityMethod
    void freezeAmount(String accountId, Long amount);

    @ActivityMethod
    void unfreezeAmount(String accountId, Long amount);

    @ActivityMethod
    void createLedgerEntry(String paymentId, String fromAccount, String toAccount, Long amount);

    @ActivityMethod
    void reverseLedgerEntry(String paymentId);

    @ActivityMethod
    void transfer(String fromAccount, String toAccount, Long amount);

    @ActivityMethod
    void reverseTransfer(String fromAccount, String toAccount, Long amount);

    @ActivityMethod
    void markProcessing(String paymentId);

    @ActivityMethod
    void markCompleted(String paymentId);

    @ActivityMethod
    void markFailed(String paymentId, String reason);

    @ActivityMethod
    void sendCompletedNotification(String paymentId);

    @ActivityMethod
    void sendFailedNotification(String paymentId, String reason);
}
