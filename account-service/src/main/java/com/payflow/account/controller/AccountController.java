package com.payflow.account.controller;

import com.payflow.account.domain.Account;
import com.payflow.account.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String accountId) {
        Account account = accountService.getAccount(accountId);
        return ResponseEntity.ok(Map.of(
                "account_id", account.getAccountId(),
                "available_balance", account.getAvailableBalance(),
                "frozen_balance", account.getFrozenBalance(),
                "currency", account.getCurrency(),
                "updated_at", account.getUpdatedAt().toString()
        ));
    }

    @PostMapping("/freeze")
    public ResponseEntity<Void> freeze(
            @RequestParam("account_id") String accountId,
            @RequestParam("amount") Long amount) {
        accountService.freezeAmount(accountId, amount);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/unfreeze")
    public ResponseEntity<Void> unfreeze(
            @RequestParam("account_id") String accountId,
            @RequestParam("amount") Long amount) {
        accountService.unfreezeAmount(accountId, amount);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/transfer")
    public ResponseEntity<Void> transfer(
            @RequestParam("from_account") String fromAccount,
            @RequestParam("to_account") String toAccount,
            @RequestParam("amount") Long amount) {
        accountService.transfer(fromAccount, toAccount, amount);
        return ResponseEntity.ok().build();
    }
}
