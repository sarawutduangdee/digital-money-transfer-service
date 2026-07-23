package com.bank.transfer.service;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.AccountStatus;
import com.bank.transfer.domain.EntryType;
import com.bank.transfer.domain.LedgerEntry;
import com.bank.transfer.domain.OutboxEvent;
import com.bank.transfer.domain.OutboxStatus;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatus;
import com.bank.transfer.dto.TransferRequest;
import com.bank.transfer.dto.TransferResponse;
import com.bank.transfer.exception.AccountNotFoundException;
import com.bank.transfer.exception.BusinessException;
import com.bank.transfer.exception.InsufficientBalanceException;
import com.bank.transfer.repository.AccountRepository;
import com.bank.transfer.repository.LedgerEntryRepository;
import com.bank.transfer.repository.OutboxEventRepository;
import com.bank.transfer.repository.TransferRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public TransferResponse processTransfer(String idempotencyKey, TransferRequest request) {
// --- 1. ตรวจสอบ Idempotency ตามสเปค ---
        Optional<Transfer> existingTransferOpt = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTransferOpt.isPresent()) {
            Transfer existing = existingTransferOpt.get();

            // เช็คว่า Payload เหมือนเดิมหรือไม่
            boolean isSamePayload = existing.getFromAccount().getAccountNumber().equals(request.getFromAccountNumber())
                                    && existing.getToAccount().getAccountNumber().equals(request.getToAccountNumber())
                                    && existing.getAmount().compareTo(request.getAmount()) == 0
                                    && existing.getCurrency().equals(request.getCurrency());

            if (isSamePayload) {
                // ส่งคีย์เดิม + Payload เดิม -> คืนผลลัพธ์เดิมโดยไม่หักเงินซ้ำ
                return buildTransferResponse(existing, existing.getFromAccount(), existing.getToAccount());
            } else {
                // ส่งคีย์เดิม + Payload เปลี่ยน -> 409 Conflict
                throw new BusinessException(HttpStatus.CONFLICT, "ERR_TRANSFER_001", "Idempotency-Key already used with a different payload");
            }
        }

        // --- 2. ตรวจสอบ Business Rules (422 Unprocessable Entity) ---
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_001", "Transfer amount must be greater than zero");
        }
        if (request.getFromAccountNumber().equals(request.getToAccountNumber())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_002", "Cannot transfer to the same account");
        }

        // --- 3. Lock Accounts อย่างมีลำดับ ---
        String fromAccStr = request.getFromAccountNumber();
        String toAccStr = request.getToAccountNumber();
        boolean isFromFirst = fromAccStr.compareTo(toAccStr) < 0;

        String firstLockAcc = isFromFirst ? fromAccStr : toAccStr;
        String secondLockAcc = isFromFirst ? toAccStr : fromAccStr;

        // ถ้าหาไม่เจอจะ Throw 404 Account Not Found ตามสเปค
        Account firstAccount = accountRepository.findByAccountNumberForUpdate(firstLockAcc)
            .orElseThrow(() -> new AccountNotFoundException(firstLockAcc));
        Account secondAccount = accountRepository.findByAccountNumberForUpdate(secondLockAcc)
            .orElseThrow(() -> new AccountNotFoundException(secondLockAcc));

        Account fromAccount = request.getFromAccountNumber().equals(firstAccount.getAccountNumber()) ? firstAccount : secondAccount;
        Account toAccount = request.getToAccountNumber().equals(firstAccount.getAccountNumber()) ? firstAccount : secondAccount;

        // --- 4. ตรวจสอบ Business Rules ส่วนของบัญชี (422 Unprocessable Entity) ---
        if (fromAccount.getStatus() != AccountStatus.ACTIVE || toAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_003", "One or both accounts are not in ACTIVE status");
        }
        if (!fromAccount.getCurrency().equals(request.getCurrency()) || !toAccount.getCurrency().equals(request.getCurrency())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_004", "Currency mismatch between accounts and request");
        }
        if (fromAccount.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientBalanceException(fromAccount.getAccountNumber()); // ตัวนี้เป็น 422 อยู่แล้ว
        }

        // --- 5. ดำเนินการหัก/เพิ่มเงิน และบันทึกลง Database ---
        fromAccount.setBalance(fromAccount.getBalance().subtract(request.getAmount()));
        toAccount.setBalance(toAccount.getBalance().add(request.getAmount()));
        accountRepository.saveAll(List.of(fromAccount, toAccount));

        Transfer transfer = Transfer.builder()
            .idempotencyKey(idempotencyKey)
            .fromAccount(fromAccount)
            .toAccount(toAccount)
            .amount(request.getAmount())
            .currency(request.getCurrency())
            .status(TransferStatus.COMPLETED)
            .requestHash("HASH_PLACEHOLDER")
            .build();
        transfer = transferRepository.save(transfer);

        LedgerEntry debitEntry = LedgerEntry.builder()
            .account(fromAccount).transfer(transfer).entryType(EntryType.DEBIT)
            .amount(request.getAmount()).balanceAfter(fromAccount.getBalance()).build();
        LedgerEntry creditEntry = LedgerEntry.builder()
            .account(toAccount).transfer(transfer).entryType(EntryType.CREDIT)
            .amount(request.getAmount()).balanceAfter(toAccount.getBalance()).build();
        ledgerEntryRepository.saveAll(List.of(debitEntry, creditEntry));

        try {
            OutboxEvent event = OutboxEvent.builder()
                .aggregateType("Transfer").aggregateId(String.valueOf(transfer.getId()))
                .eventType("TransferCompleted").payload(objectMapper.writeValueAsString(transfer))
                .status(OutboxStatus.PENDING).build();
            outboxEventRepository.save(event);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize transfer event for outbox", e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "ERR_SYS_002", "Failed to generate event payload");
        }

        return buildTransferResponse(transfer, fromAccount, toAccount);
    }

    // Helper Method สำหรับสร้าง Response เพื่อลดความซ้ำซ้อนของโค้ด
    private TransferResponse buildTransferResponse(Transfer transfer, Account fromAccount, Account toAccount) {
        return TransferResponse.builder()
            .transactionId(String.valueOf(transfer.getId()))
            .fromAccountNumber(fromAccount.getAccountNumber())
            .toAccountNumber(toAccount.getAccountNumber())
            .amount(transfer.getAmount().setScale(2, RoundingMode.HALF_UP))
            .currency(transfer.getCurrency())
            .status(transfer.getStatus().name())
            .createdAt(transfer.getCreatedAt())
            .build();
    }
}
