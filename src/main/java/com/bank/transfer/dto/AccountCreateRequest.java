package com.bank.transfer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountCreateRequest {
    @NotBlank(message = "OwnerName is required")
    private String ownerName;

    @NotNull(message = "Currency is required")
    @Pattern(regexp = "^THB$", message = "currency must be THB")
    private String currency;

    @NotNull(message = "initialBalance is required")
    @DecimalMin(value = "0.00", message = "initialBalance must be greater than or equal to 0")
    private BigDecimal initialBalance;

}
