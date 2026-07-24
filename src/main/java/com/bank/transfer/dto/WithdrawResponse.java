package com.bank.transfer.dto;

import java.math.BigDecimal;

public record WithdrawResponse(
    Long accountId,
    BigDecimal balance,
    Long ledgerEntryId
) {
}
