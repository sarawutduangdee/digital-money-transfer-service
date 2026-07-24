package com.bank.transfer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferCompletedEvent(
    String eventId,
    String eventType,
    Long transferId,
    Long fromAccountId,
    Long toAccountId,
    BigDecimal amount,
    String currency,
    Instant occurredAt) {
    public TransferCompletedEvent(Long transferId, Long fromAccountId, Long toAccountId, BigDecimal amount, String currency) {
        this(
            "evt-" + UUID.randomUUID().toString(),
            "TransferCompleted",
            transferId,
            fromAccountId,
            toAccountId,
            amount,
            currency,
            Instant.now()
        );
    }
}
