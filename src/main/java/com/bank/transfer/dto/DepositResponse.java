package com.bank.transfer.dto;

import java.math.BigDecimal;

public record DepositResponse(
    Long accountId,
    BigDecimal balance,
    Long ledgerEntryId
) {
}
