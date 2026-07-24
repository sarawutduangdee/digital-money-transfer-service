package com.bank.transfer.service;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.AccountStatus;
import com.bank.transfer.domain.EntryType;
import com.bank.transfer.domain.LedgerEntry;
import com.bank.transfer.domain.OutboxEvent;
import com.bank.transfer.domain.OutboxStatus;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatus;
import com.bank.transfer.dto.IdempotencyRecord;
import com.bank.transfer.dto.TransferCompletedEvent;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final DistributedLockService distributedLockService;
    private final CacheManager cacheManager;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:transfer:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

    public TransferResponse processTransfer(String idempotencyKey, TransferRequest request) {
        String cacheKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
        String currentHash = calculateRequestHash(request);

        String cachedJson = redisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            try {
                IdempotencyRecord record = objectMapper.readValue(cachedJson, IdempotencyRecord.class);
                if (record.requestHash().equals(currentHash)) {
                    log.info("Idempotency hit from Redis cache for key: {}", idempotencyKey);
                    return record.response();
                } else {
                    throw new BusinessException(HttpStatus.CONFLICT, "ERR_TRANSFER_001", "Idempotency-Key already used with a different payload");
                }
            } catch (JsonProcessingException e) {
                log.error("Failed to parse cached idempotency record", e);
            }
        }

        Optional<Transfer> existingTransferOpt = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTransferOpt.isPresent()) {
            Transfer existing = existingTransferOpt.get();

            boolean isSamePayload = existing.getFromAccount().getAccountNumber().equals(request.getFromAccountNumber())
                                    && existing.getToAccount().getAccountNumber().equals(request.getToAccountNumber())
                                    && existing.getAmount().compareTo(request.getAmount()) == 0
                                    && existing.getCurrency().equals(request.getCurrency());

            if (isSamePayload) {
                TransferResponse response = buildTransferResponse(existing, existing.getFromAccount(), existing.getToAccount());
                cacheIdempotencyResult(cacheKey, currentHash, response);
                return response;
            } else {
                throw new BusinessException(HttpStatus.CONFLICT, "ERR_TRANSFER_001", "Idempotency-Key already used with a different payload");
            }
        }

        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_001", "Transfer amount must be greater than zero");
        }
        if (request.getFromAccountNumber().equals(request.getToAccountNumber())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_002", "Cannot transfer to the same account");
        }

        Account fromAccObj = accountRepository.findByAccountNumber(request.getFromAccountNumber())
            .orElseThrow(() -> new AccountNotFoundException(request.getFromAccountNumber()));
        Account toAccObj = accountRepository.findByAccountNumber(request.getToAccountNumber())
            .orElseThrow(() -> new AccountNotFoundException(request.getToAccountNumber()));

        TransferResponse response = distributedLockService.executeWithTwoAccountsLock(fromAccObj.getId(), toAccObj.getId(), () ->
            executeTransferInTransaction(idempotencyKey, request, fromAccObj.getId(), toAccObj.getId())
        );

        cacheIdempotencyResult(cacheKey, currentHash, response);

        return response;
    }

    @Transactional
    public TransferResponse executeTransferInTransaction(String idempotencyKey, TransferRequest request, Long fromId, Long toId) {

        Optional<Transfer> existingTransferOpt = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTransferOpt.isPresent()) {
            Transfer existing = existingTransferOpt.get();
            return buildTransferResponse(existing, existing.getFromAccount(), existing.getToAccount());
        }

        String fromAccStr = request.getFromAccountNumber();
        String toAccStr = request.getToAccountNumber();
        boolean isFromFirst = fromAccStr.compareTo(toAccStr) < 0;

        String firstLockAcc = isFromFirst ? fromAccStr : toAccStr;
        String secondLockAcc = isFromFirst ? toAccStr : fromAccStr;

        Account firstAccount = accountRepository.findByAccountNumberForUpdate(firstLockAcc)
            .orElseThrow(() -> new AccountNotFoundException(firstLockAcc));
        Account secondAccount = accountRepository.findByAccountNumberForUpdate(secondLockAcc)
            .orElseThrow(() -> new AccountNotFoundException(secondLockAcc));

        Account fromAccount = request.getFromAccountNumber().equals(firstAccount.getAccountNumber()) ? firstAccount : secondAccount;
        Account toAccount = request.getToAccountNumber().equals(firstAccount.getAccountNumber()) ? firstAccount : secondAccount;

        if (fromAccount.getStatus() != AccountStatus.ACTIVE || toAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_003", "One or both accounts are not in ACTIVE status");
        }
        if (!fromAccount.getCurrency().equals(request.getCurrency()) || !toAccount.getCurrency().equals(request.getCurrency())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_004", "Currency mismatch between accounts and request");
        }
        if (fromAccount.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientBalanceException(fromAccount.getAccountNumber());
        }

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
            TransferCompletedEvent eventPayload = new TransferCompletedEvent(
                transfer.getId(),
                fromAccount.getId(),
                toAccount.getId(),
                transfer.getAmount(),
                transfer.getCurrency()
            );

            OutboxEvent event = OutboxEvent.builder()
                .aggregateType("Traznsfer").aggregateId(String.valueOf(transfer.getId()))
                .eventType("TransferCompleted").payload(objectMapper.writeValueAsString(eventPayload))
                .status(OutboxStatus.PENDING).build();
            outboxEventRepository.save(event);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize transfer event for outbox", e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "ERR_SYS_002", "Failed to generate event payload");
        }

        evictAccountCache(fromAccount.getId());
        evictAccountCache(toAccount.getId());

        return buildTransferResponse(transfer, fromAccount, toAccount);
    }

    private void cacheIdempotencyResult(String cacheKey, String requestHash, TransferResponse response) {
        try {
            IdempotencyRecord record = new IdempotencyRecord(requestHash, response);
            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForValue().set(cacheKey, json, IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("Failed to save idempotency result to Redis cache", e);
        }
    }

    private void evictAccountCache(Long accountId) {
        Cache accountCache = cacheManager.getCache("account");
        if (accountCache != null) {
            accountCache.evict(accountId);
        }
    }

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

    @Transactional(readOnly = true)
    public TransferResponse getTransferById(Long id) {
        Transfer transfer = transferRepository.findById(id)
            .orElseThrow(() -> new BusinessException(
                HttpStatus.NOT_FOUND,
                "ERR_TRANSFER_002",
                "Transfer transaction not found with id: " + id
            ));

        return buildTransferResponse(
            transfer,
            transfer.getFromAccount(),
            transfer.getToAccount()
        );
    }

    private String calculateRequestHash(TransferRequest request) {
        try {
            String raw = request.getFromAccountNumber() + ":"
                         + request.getToAccountNumber() + ":"
                         + request.getAmount().setScale(2, RoundingMode.HALF_UP) + ":"
                         + request.getCurrency();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(request.hashCode());
        }
    }
}
