package com.bank.transfer.controller;

import com.bank.transfer.dto.TransferRequest;
import com.bank.transfer.exception.AccountNotFoundException;
import com.bank.transfer.exception.BusinessException;
import com.bank.transfer.service.TransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
public class TransferControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransferService transferService;

    @Test
    void transferMoney_Returns400_WhenIdempotencyKeyMissing() throws Exception {
        TransferRequest request = new TransferRequest("1001", "2002", new BigDecimal("500"), "THB");

        mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            // ไม่ส่ง Header Idempotency-Key
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("ERR_REQ_001"));
    }

    @Test
    void transferMoney_Returns400_WhenBodyIsInvalid() throws Exception {
        // สร้าง Request ที่ amount เป็น 0 (ผิด Validation @DecimalMin)
        TransferRequest request = new TransferRequest("1001", "2002", BigDecimal.ZERO, "THB");

        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", "req-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("ERR_REQ_002"))
            .andExpect(jsonPath("$.invalidFields.amount").exists());
    }

    @Test
    void transferMoney_Returns404_WhenAccountNotFound() throws Exception {
        TransferRequest request = new TransferRequest("9999", "2002", new BigDecimal("500"), "THB");

        when(transferService.processTransfer(eq("req-123"), any(TransferRequest.class)))
            .thenThrow(new AccountNotFoundException("9999"));

        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", "req-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("ERR_ACCOUNT_001"));
    }

    @Test
    void transferMoney_Returns409_WhenIdempotencyConflict() throws Exception {
        TransferRequest request = new TransferRequest("1001", "2002", new BigDecimal("500"), "THB");

        when(transferService.processTransfer(eq("req-123"), any(TransferRequest.class)))
            .thenThrow(new BusinessException(HttpStatus.CONFLICT, "ERR_TRANSFER_001", "Conflict"));

        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", "req-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("ERR_TRANSFER_001"));
    }

    @Test
    void transferMoney_Returns422_WhenBusinessRuleFails() throws Exception {
        TransferRequest request = new TransferRequest("1001", "2002", new BigDecimal("500"), "THB");

        // จำลองเคสเงินไม่พอ (422)
        when(transferService.processTransfer(eq("req-123"), any(TransferRequest.class)))
            .thenThrow(new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_ACCOUNT_002", "Insufficient balance"));

        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", "req-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("ERR_ACCOUNT_002"));
    }
}
