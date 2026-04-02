package com.payflow.payment.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "ledger-service", url = "${services.ledger.url}", fallbackFactory = LedgerClientFallbackFactory.class)
public interface LedgerClient {

    @PostMapping("/api/v1/ledger/entries")
    void createEntry(
            @RequestParam("reference_id") String referenceId,
            @RequestParam("debit_account") String debitAccount,
            @RequestParam("credit_account") String creditAccount,
            @RequestParam("amount") Long amount);

    @PostMapping("/api/v1/ledger/entries/reverse")
    void reverseEntry(
            @RequestParam("reference_id") String referenceId,
            @RequestParam("debit_account") String debitAccount,
            @RequestParam("credit_account") String creditAccount,
            @RequestParam("amount") Long amount);
}
