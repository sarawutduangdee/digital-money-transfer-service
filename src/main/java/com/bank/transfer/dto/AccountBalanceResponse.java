package com.bank.transfer.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountBalanceResponse(
    Long accountId,
    BigDecimal balance,
    String currency,
    LocalDateTime asOf
) {
}
