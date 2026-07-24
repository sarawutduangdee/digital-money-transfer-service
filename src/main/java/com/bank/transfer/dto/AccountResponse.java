package com.bank.transfer.dto;

import com.bank.transfer.domain.AccountStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountResponse {
    private Long id;
    private String accountNumber;
    private String ownerName;
    private String currency;
    private BigDecimal balance;
    private AccountStatus status;
    private LocalDateTime createdAt;
}
