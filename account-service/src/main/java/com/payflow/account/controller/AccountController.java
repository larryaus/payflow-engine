package com.payflow.account.controller;

import com.payflow.account.domain.Account;
import com.payflow.account.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listAccounts() {
        List<Map<String, Object>> accounts = accountService.listAccounts().stream()
                .map(a -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("account_id", a.getAccountId());
                    m.put("account_name", a.getAccountName());
                    m.put("available_balance", a.getAvailableBalance());
                    m.put("frozen_balance", a.getFrozenBalance());
                    m.put("currency", a.getCurrency());
                    m.put("updated_at", a.getUpdatedAt().toString());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(accounts);
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
        if (amount == null || amount <= 0) {
            throw new IllegalStateException("Amount must be positive");
        }
        accountService.freezeAmount(accountId, amount);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/unfreeze")
    public ResponseEntity<Void> unfreeze(
            @RequestParam("account_id") String accountId,
            @RequestParam("amount") Long amount) {
        if (amount == null || amount <= 0) {
            throw new IllegalStateException("Amount must be positive");
        }
        accountService.unfreezeAmount(accountId, amount);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/transfer")
    public ResponseEntity<Void> transfer(
            @RequestParam("from_account") String fromAccount,
            @RequestParam("to_account") String toAccount,
            @RequestParam("amount") Long amount) {
        if (amount == null || amount <= 0) {
            throw new IllegalStateException("Amount must be positive");
        }
        if (fromAccount.equals(toAccount)) {
            throw new IllegalStateException("Cannot transfer to the same account");
        }
        accountService.transfer(fromAccount, toAccount, amount);
        return ResponseEntity.ok().build();
    }
}
