package com.bank.transfer.service;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.AccountStatus;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.dto.TransferRequest;
import com.bank.transfer.dto.TransferResponse;
import com.bank.transfer.exception.BusinessException;
import com.bank.transfer.exception.InsufficientBalanceException;
import com.bank.transfer.repository.AccountRepository;
import com.bank.transfer.repository.LedgerEntryRepository;
import com.bank.transfer.repository.OutboxEventRepository;
import com.bank.transfer.repository.TransferRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TransferServiceTest {
    @Mock
    private AccountRepository accountRepository;
    @Mock private TransferRepository transferRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private TransferService transferService;

    private TransferRequest validRequest;
    private Account fromAccount;
    private Account toAccount;

    @BeforeEach
    void setUp() {
        validRequest = TransferRequest.builder()
            .fromAccountNumber("1001")
            .toAccountNumber("2002")
            .amount(new BigDecimal("500.00"))
            .currency("THB")
            .build();

        fromAccount = Account.builder()
            .accountNumber("1001")
            .balance(new BigDecimal("1000.00"))
            .currency("THB")
            .status(AccountStatus.ACTIVE)
            .build();

        toAccount = Account.builder()
            .accountNumber("2002")
            .balance(new BigDecimal("500.00"))
            .currency("THB")
            .status(AccountStatus.ACTIVE)
            .build();
    }

    @Test
    void processTransfer_Success() throws Exception {
        // Arrange
        when(transferRepository.findByIdempotencyKey("req-123")).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumberForUpdate("1001")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumberForUpdate("2002")).thenReturn(Optional.of(toAccount));
        when(transferRepository.save(any(Transfer.class))).thenAnswer(i -> {
            Transfer t = i.getArgument(0);
            t.setId(1L);
            return t;
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        // Act
        TransferResponse response = transferService.processTransfer("req-123", validRequest);

        // Assert
        assertNotNull(response);
        assertEquals("1001", response.getFromAccountNumber());
        assertEquals(new BigDecimal("500.00"), fromAccount.getBalance()); // 1000 - 500
        assertEquals(new BigDecimal("1000.00"), toAccount.getBalance()); // 500 + 500

        verify(accountRepository, times(1)).saveAll(any());
        verify(ledgerEntryRepository, times(1)).saveAll(any());
        verify(outboxEventRepository, times(1)).save(any());
    }

    @Test
    void processTransfer_ThrowsException_WhenInsufficientBalance() {
        // Arrange
        fromAccount.setBalance(new BigDecimal("100.00")); // เงินไม่พอ (โอน 500)
        when(transferRepository.findByIdempotencyKey("req-123")).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumberForUpdate("1001")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumberForUpdate("2002")).thenReturn(Optional.of(toAccount));

        // Act & Assert
        assertThrows(InsufficientBalanceException.class, () ->
            transferService.processTransfer("req-123", validRequest)
        );
    }

    @Test
    void processTransfer_ThrowsException_WhenTransferToSameAccount() {
        validRequest.setToAccountNumber("1001"); // โอนเข้าตัวเอง

        BusinessException ex = assertThrows(BusinessException.class, () ->
            transferService.processTransfer("req-123", validRequest)
        );
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("ERR_RULE_002", ex.getErrorCode());
    }
}
