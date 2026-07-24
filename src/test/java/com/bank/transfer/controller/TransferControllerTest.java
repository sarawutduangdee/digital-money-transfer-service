package com.bank.transfer.controller;

import com.bank.transfer.dto.TransferRequest;
import com.bank.transfer.dto.TransferResponse;
import com.bank.transfer.service.TransferService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransferService transferService;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    private static final String BASE_URL = "/transfers";

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
    }

    @Test
    @DisplayName("POST /transfers - โอนเงินสำเร็จ คืนค่า HTTP 201 CREATED")
    void processTransfer_Success() throws Exception {
        TransferRequest request = new TransferRequest("0000001001", "0000002002", new BigDecimal("100.00"), "THB");
        TransferResponse response = TransferResponse.builder()
            .transactionId("1001")
            .fromAccountNumber("0000001001")
            .toAccountNumber("0000002002")
            .amount(new BigDecimal("100.00"))
            .currency("THB")
            .status("COMPLETED")
            .createdAt(LocalDateTime.now())
            .build();

        when(transferService.processTransfer(anyString(), any(TransferRequest.class))).thenReturn(response);

        mockMvc.perform(post(BASE_URL)
                .header("Idempotency-Key", "test-idem-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.transactionId").value("1001"));
    }

    @Test
    @DisplayName("POST /transfers - ถ้าไม่ส่ง Idempotency-Key ต้องคืนค่า HTTP 400 Bad Request")
    void processTransfer_MissingIdempotencyKey_ShouldReturn400() throws Exception {
        TransferRequest request = new TransferRequest("0000001001", "0000002002", new BigDecimal("100.00"), "THB");

        mockMvc.perform(post(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /transfers - ถ้ายอดเงินติดลบ (Validation) ต้องคืนค่า HTTP 422 Unprocessable Entity")
    void processTransfer_NegativeAmount_ShouldReturn400() throws Exception {
        TransferRequest request = new TransferRequest("0000001001", "0000002002", new BigDecimal("-50.00"), "THB");

        mockMvc.perform(post(BASE_URL)
                .header("Idempotency-Key", "test-idem-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity());
    }
}
