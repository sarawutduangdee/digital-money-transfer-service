package com.bank.transfer.controller;

import com.bank.transfer.dto.TransferRequest;
import com.bank.transfer.dto.TransferResponse;
import com.bank.transfer.exception.BusinessException;
import com.bank.transfer.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
public class TransferController {
    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<TransferResponse> transferMoney(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody TransferRequest request
    ) {
        if (idempotencyKey.trim().isEmpty()) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "ERR_REQ_001",
                "Idempotency-Key header must not be blank"
            );
        }

        TransferResponse response = transferService.processTransfer(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
