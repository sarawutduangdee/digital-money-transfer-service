package com.bank.transfer.exception;

import org.springframework.http.HttpStatus;

public class TransferAlreadyProcessedException extends BusinessException {
    public TransferAlreadyProcessedException(String idempotencyKey) {
        super(
            HttpStatus.CONFLICT,
            "ERR_TRANSFER_001",
            "Transfer request with idempotency key " + idempotencyKey + " has already been processed"
        );
    }
}
