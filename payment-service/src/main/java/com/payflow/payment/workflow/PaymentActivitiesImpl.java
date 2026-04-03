package com.payflow.payment.workflow;

import com.payflow.payment.client.AccountClient;
import com.payflow.payment.client.LedgerClient;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.mq.PaymentEventProducer;
import com.payflow.payment.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentActivitiesImpl implements PaymentActivities {

    private static final Logger log = LoggerFactory.getLogger(PaymentActivitiesImpl.class);

    private final AccountClient accountClient;
    private final LedgerClient ledgerClient;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentEventProducer eventProducer;

    public PaymentActivitiesImpl(AccountClient accountClient,
                                 LedgerClient ledgerClient,
                                 PaymentOrderRepository paymentOrderRepository,
                                 PaymentEventProducer eventProducer) {
        this.accountClient = accountClient;
        this.ledgerClient = ledgerClient;
        this.paymentOrderRepository = paymentOrderRepository;
        this.eventProducer = eventProducer;
    }

    @Override
    public void freezeAmount(String accountId, Long amount) {
        log.info("Freezing amount {} for account {}", amount, accountId);
        accountClient.freezeAmount(accountId, amount);
    }

    @Override
    public void unfreezeAmount(String accountId, Long amount) {
        log.info("Unfreezing amount {} for account {}", amount, accountId);
        accountClient.unfreezeAmount(accountId, amount);
    }

    @Override
    public void createLedgerEntry(String paymentId, String fromAccount, String toAccount, Long amount) {
        log.info("Creating ledger entry for payment {}", paymentId);
        ledgerClient.createEntry(paymentId, fromAccount, toAccount, amount);
    }

    @Override
    public void reverseLedgerEntry(String paymentId) {
        log.info("Reversing ledger entry for payment {}", paymentId);
        ledgerClient.reverseEntry(paymentId);
    }

    @Override
    public void transfer(String fromAccount, String toAccount, Long amount) {
        log.info("Executing transfer from {} to {} amount {}", fromAccount, toAccount, amount);
        accountClient.transfer(fromAccount, toAccount, amount);
    }

    @Override
    public void reverseTransfer(String fromAccount, String toAccount, Long amount) {
        log.info("Reversing transfer from {} to {} amount {}", fromAccount, toAccount, amount);
        accountClient.reverseTransfer(fromAccount, toAccount, amount);
    }

    @Override
    public void markProcessing(String paymentId) {
        PaymentOrder order = findOrder(paymentId);
        order.transitTo(PaymentStatus.PROCESSING);
        paymentOrderRepository.save(order);
    }

    @Override
    public void markCompleted(String paymentId) {
        PaymentOrder order = findOrder(paymentId);
        order.transitTo(PaymentStatus.COMPLETED);
        paymentOrderRepository.save(order);
        log.info("Payment {} completed", paymentId);
    }

    @Override
    public void markFailed(String paymentId, String reason) {
        PaymentOrder order = findOrder(paymentId);
        order.transitTo(PaymentStatus.FAILED);
        paymentOrderRepository.save(order);
        log.error("Payment {} failed: {}", paymentId, reason);
    }

    @Override
    public void sendCompletedNotification(String paymentId) {
        PaymentOrder order = findOrder(paymentId);
        eventProducer.sendPaymentCompleted(order);
    }

    @Override
    public void sendFailedNotification(String paymentId, String reason) {
        PaymentOrder order = findOrder(paymentId);
        eventProducer.sendPaymentFailed(order, reason);
    }

    private PaymentOrder findOrder(String paymentId) {
        return paymentOrderRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + paymentId));
    }
}
