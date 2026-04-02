package com.payflow.payment.client;

import com.payflow.payment.exception.PaymentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class AccountClientFallbackFactory implements FallbackFactory<AccountClient> {

    private static final Logger log = LoggerFactory.getLogger(AccountClientFallbackFactory.class);

    @Override
    public AccountClient create(Throwable cause) {
        return new AccountClient() {
            @Override
            public void freezeAmount(String accountId, Long amount) {
                log.error("Account service unavailable on freezeAmount. account={} amount={}: {}",
                        accountId, amount, cause.getMessage());
                throw new PaymentException("SERVICE_UNAVAILABLE", "账户服务不可用，无法冻结资金");
            }

            @Override
            public void unfreezeAmount(String accountId, Long amount) {
                log.error("Account service unavailable on unfreezeAmount. account={} amount={}: {}",
                        accountId, amount, cause.getMessage());
                throw new PaymentException("SERVICE_UNAVAILABLE", "账户服务不可用，无法解冻资金");
            }

            @Override
            public void transfer(String fromAccount, String toAccount, Long amount) {
                log.error("Account service unavailable on transfer. from={} to={} amount={}: {}",
                        fromAccount, toAccount, amount, cause.getMessage());
                throw new PaymentException("SERVICE_UNAVAILABLE", "账户服务不可用，无法完成转账");
            }
        };
    }
}
