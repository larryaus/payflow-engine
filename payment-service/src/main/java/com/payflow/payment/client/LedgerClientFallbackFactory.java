package com.payflow.payment.client;

import com.payflow.payment.exception.PaymentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class LedgerClientFallbackFactory implements FallbackFactory<LedgerClient> {

    private static final Logger log = LoggerFactory.getLogger(LedgerClientFallbackFactory.class);

    @Override
    public LedgerClient create(Throwable cause) {
        return new LedgerClient() {
            @Override
            public void createEntry(String referenceId, String debitAccount, String creditAccount, Long amount) {
                FallbackPolicy.rethrowIfClientError(cause);
                log.error("Ledger service unavailable on createEntry. ref={} debit={} credit={} amount={}: {}",
                        referenceId, debitAccount, creditAccount, amount, cause.getMessage());
                throw new PaymentException("SERVICE_UNAVAILABLE", "记账服务不可用，无法创建账务分录");
            }

            @Override
            public void reverseEntry(String referenceId, String debitAccount, String creditAccount, Long amount) {
                FallbackPolicy.rethrowIfClientError(cause);
                log.error("Ledger service unavailable on reverseEntry. ref={} debit={} credit={} amount={}: {}",
                        referenceId, debitAccount, creditAccount, amount, cause.getMessage());
                throw new PaymentException("SERVICE_UNAVAILABLE", "记账服务不可用，无法撤销账务分录");
            }
        };
    }
}
