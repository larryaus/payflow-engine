package com.payflow.payment.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "account-service", url = "${services.account.url}", fallbackFactory = AccountClientFallbackFactory.class)
public interface AccountClient {

    @PostMapping("/api/v1/accounts/freeze")
    void freezeAmount(
            @RequestParam("account_id") String accountId,
            @RequestParam("amount") Long amount);

    @PostMapping("/api/v1/accounts/unfreeze")
    void unfreezeAmount(
            @RequestParam("account_id") String accountId,
            @RequestParam("amount") Long amount);

    @PostMapping("/api/v1/accounts/transfer")
    void transfer(
            @RequestParam("from_account") String fromAccount,
            @RequestParam("to_account") String toAccount,
            @RequestParam("amount") Long amount);

    @PostMapping("/api/v1/accounts/reverse-transfer")
    void reverseTransfer(
            @RequestParam("from_account") String fromAccount,
            @RequestParam("to_account") String toAccount,
            @RequestParam("amount") Long amount);
}
