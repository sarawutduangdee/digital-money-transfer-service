package com.bank.transfer.dto;

import com.bank.transfer.domain.EntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public record AccountStatementResponse(
    Long accountId,
    int page,
    int size,
    long totalElements,
    int totalPages,
    List<StatementItem> items
) {
    public record StatementItem(
        Long id,
        EntryType entryType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Long transferId,
        LocalDateTime createdAt
    ) {}
}
