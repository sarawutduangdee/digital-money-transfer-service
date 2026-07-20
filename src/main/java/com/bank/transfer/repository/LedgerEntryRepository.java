package com.bank.transfer.repository;

import com.bank.transfer.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(Long accountId);
}
