package com.bank.transfer.dto;

import com.bank.transfer.domain.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(
    @NotNull(message = "status is required")
    AccountStatus status
) {
}
