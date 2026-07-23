package com.bank.transfer.exception;

import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends BusinessException {
    public AccountNotFoundException(String accountNumber) {
        super(
            HttpStatus.NOT_FOUND,
            "ERR_ACCOUNT_001",
            "Account not found for account number: " + accountNumber
        );
    }
}
