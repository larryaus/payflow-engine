package com.payflow.ledger.controller;

import com.payflow.ledger.domain.LedgerEntry;
import com.payflow.ledger.service.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/entries")
    public ResponseEntity<LedgerEntry> createEntry(
            @RequestParam("reference_id") String referenceId,
            @RequestParam("debit_account") String debitAccount,
            @RequestParam("credit_account") String creditAccount,
            @RequestParam("amount") Long amount) {
        LedgerEntry entry = ledgerService.createEntry(referenceId, debitAccount, creditAccount, amount);
        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    @PostMapping("/entries/reverse")
    public ResponseEntity<LedgerEntry> reverseEntry(
            @RequestParam("payment_id") String paymentId) {
        LedgerEntry reversal = ledgerService.reverseEntry(paymentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(reversal);
    }

    @GetMapping("/entries")
    public ResponseEntity<List<LedgerEntry>> getEntries(@RequestParam("payment_id") String paymentId) {
        return ResponseEntity.ok(ledgerService.getEntriesByPaymentId(paymentId));
    }

    @GetMapping("/verify")
    public ResponseEntity<Boolean> verifyBalance() {
        return ResponseEntity.ok(ledgerService.verifyBalance());
    }
}
