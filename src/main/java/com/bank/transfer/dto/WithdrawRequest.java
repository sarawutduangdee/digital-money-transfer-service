package com.bank.transfer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record WithdrawRequest(
    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "Withdrawal amount must be greater than zero")
    BigDecimal amount
) {
}
