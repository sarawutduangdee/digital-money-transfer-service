package com.bank.transfer.controller;

import com.bank.transfer.domain.AccountStatus;
import com.bank.transfer.dto.AccountResponse;
import com.bank.transfer.dto.DepositRequest;
import com.bank.transfer.dto.DepositResponse;
import com.bank.transfer.dto.WithdrawRequest;
import com.bank.transfer.dto.WithdrawResponse;
import com.bank.transfer.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
public class AccountControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AccountService accountService;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    private static final String BASE_URL = "/accounts";

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
    }

    @Test
    @DisplayName("GET /accounts/{id} - ดึงข้อมูลบัญชีด้วย ID สำเร็จ คืนค่า HTTP 200 OK")
    void getAccountById_Success() throws Exception {
        AccountResponse response = AccountResponse.builder()
            .id(1L)
            .accountNumber("0000001001")
            .ownerName("Somchai")
            .balance(new BigDecimal("1000.00"))
            .currency("THB")
            .status(AccountStatus.ACTIVE)
            .build();

        when(accountService.getAccountById(1L)).thenReturn(response);

        mockMvc.perform(get(BASE_URL + "/1")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.accountNumber").value("0000001001"))
            .andExpect(jsonPath("$.ownerName").value("Somchai"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /accounts/{id}/deposit - ฝากเงินสำเร็จ คืนค่า HTTP 200 OK")
    void deposit_Success() throws Exception {
        DepositRequest request = new DepositRequest(new BigDecimal("500.00"));
        DepositResponse response = DepositResponse.builder()
            .accountId(1001L)
            .balance(new BigDecimal("1500.00"))
            .ledgerEntryId(51L)
            .build();

        when(accountService.deposit(anyLong(), any(BigDecimal.class))).thenReturn(response);

        mockMvc.perform(post(BASE_URL + "/1/deposit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value(1001))
            .andExpect(jsonPath("$.balance").value(1500.00))
            .andExpect(jsonPath("$.ledgerEntryId").value(51));
    }

    @Test
    @DisplayName("POST /accounts/{id}/withdraw - ถอนเงินสำเร็จ คืนค่า HTTP 200 OK")
    void withdraw_Success() throws Exception {
        WithdrawRequest request = new WithdrawRequest(new BigDecimal("200.00"));
        WithdrawResponse response = WithdrawResponse.builder()
            .accountId(1001L)
            .balance(new BigDecimal("800.00"))
            .ledgerEntryId(51L)
            .build();

        when(accountService.withdraw(anyLong(), any(BigDecimal.class))).thenReturn(response);

        mockMvc.perform(post(BASE_URL + "/1/withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value(1001))
            .andExpect(jsonPath("$.balance").value(800.00))
            .andExpect(jsonPath("$.ledgerEntryId").value(51));
    }
}
