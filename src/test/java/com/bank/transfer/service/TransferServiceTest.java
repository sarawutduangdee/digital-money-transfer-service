package com.bank.transfer.service;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.AccountStatus;
import com.bank.transfer.dto.TransferRequest;
import com.bank.transfer.exception.BusinessException;
import com.bank.transfer.exception.InsufficientBalanceException;
import com.bank.transfer.repository.AccountRepository;
import com.bank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TransferServiceTest {
    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TransferService transferService;

    private Account fromAccount;
    private Account toAccount;

    @BeforeEach
    void setUp() {
        fromAccount = Account.builder()
            .id(1L)
            .accountNumber("0000001001")
            .ownerName("Somchai")
            .balance(new BigDecimal("1000.00"))
            .currency("THB")
            .status(AccountStatus.ACTIVE)
            .build();

        toAccount = Account.builder()
            .id(2L)
            .accountNumber("0000002002")
            .ownerName("Somying")
            .balance(new BigDecimal("500.00"))
            .currency("THB")
            .status(AccountStatus.ACTIVE)
            .build();
    }

    @Test
    @DisplayName("โอนเงินหาตัวเองต้องพ่น ERR_RULE_002")
    void transferToSameAccount_shouldThrowException() {
        // Mock Redis ให้คืนค่า null (ไม่พบ cache)
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        TransferRequest request = new TransferRequest("0000001001", "0000001001", new BigDecimal("100.00"), "THB");

        assertThatThrownBy(() -> transferService.processTransfer("idem-key-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Cannot transfer to the same account");
    }

    @Test
    @DisplayName("จำนวนเงินโอน <= 0 ต้องพ่น ERR_RULE_001")
    void transferZeroOrNegativeAmount_shouldThrowException() {
        // Mock Redis ให้คืนค่า null (ไม่พบ cache)
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        TransferRequest request = new TransferRequest("0000001001", "0000002002", new BigDecimal("0.00"), "THB");

        assertThatThrownBy(() -> transferService.processTransfer("idem-key-2", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Transfer amount must be greater than zero");
    }

    @Test
    @DisplayName("ยอดเงินในบัญชีไม่พอโอน ต้องพ่น InsufficientBalanceException")
    void transferWithInsufficientBalance_shouldThrowException() {
        TransferRequest request = new TransferRequest("0000001001", "0000002002", new BigDecimal("2000.00"), "THB");

        // Mock การค้นหาแบบ Pessimistic Lock (findByAccountNumberForUpdate)
        when(accountRepository.findByAccountNumberForUpdate("0000001001")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumberForUpdate("0000002002")).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> transferService.executeTransferInTransaction("idem-key-3", request, 1L, 2L))
            .isInstanceOf(InsufficientBalanceException.class);
    }
}
