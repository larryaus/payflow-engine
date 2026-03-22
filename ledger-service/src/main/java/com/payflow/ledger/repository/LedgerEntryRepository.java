package com.payflow.ledger.repository;

import com.payflow.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByPaymentId(String paymentId);

    @Query("SELECT SUM(e.amount) FROM LedgerEntry e WHERE e.status = 'COMPLETED'")
    Long sumAllDebits();

    @Query("SELECT SUM(e.amount) FROM LedgerEntry e WHERE e.status = 'COMPLETED'")
    Long sumAllCredits();
}
