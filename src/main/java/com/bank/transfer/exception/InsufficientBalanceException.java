package com.bank.transfer.exception;

import org.springframework.http.HttpStatus;

public class InsufficientBalanceException extends BusinessException {
    public InsufficientBalanceException(String accountNumber) {
        super(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "ERR_ACCOUNT_002",
            "Insufficient balance for account number: " + accountNumber
        );
    }
}
