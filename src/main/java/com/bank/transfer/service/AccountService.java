package com.bank.transfer.service;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.AccountStatus;
import com.bank.transfer.domain.EntryType;
import com.bank.transfer.domain.LedgerEntry;
import com.bank.transfer.dto.AccountBalanceResponse;
import com.bank.transfer.dto.AccountCreateRequest;
import com.bank.transfer.dto.AccountResponse;
import com.bank.transfer.dto.AccountStatementResponse;
import com.bank.transfer.dto.DepositResponse;
import com.bank.transfer.dto.WithdrawResponse;
import com.bank.transfer.exception.AccountNotFoundException;
import com.bank.transfer.exception.BusinessException;
import com.bank.transfer.repository.AccountRepository;
import com.bank.transfer.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final DistributedLockService lockService;

    @Transactional
    public AccountResponse createAccount(AccountCreateRequest request) {
        log.info("Creating new account for owner: {}, currency: {}, initialBalance: {}",
            request.getOwnerName(), request.getCurrency(), request.getInitialBalance());

        String generatedAccountNumber = String.format("000%07d", System.currentTimeMillis() % 10000000L);

        Account account = Account.builder()
            .ownerName(request.getOwnerName())
            .currency(request.getCurrency().toUpperCase())
            .balance(request.getInitialBalance())
            .status(AccountStatus.ACTIVE)
            .accountNumber(generatedAccountNumber)
            .build();

        Account savedAccount = accountRepository.save(account);

        if (request.getInitialBalance().compareTo(BigDecimal.ZERO) > 0) {
            LedgerEntry ledger = LedgerEntry.builder()
                .account(savedAccount)
                .entryType(EntryType.CREDIT)
                .amount(request.getInitialBalance())
                .balanceAfter(request.getInitialBalance())
                .build();

            ledgerEntryRepository.save(ledger);
        }

        log.info("Account created successfully with ID: {} and AccountNumber: {}",
            savedAccount.getId(), savedAccount.getAccountNumber());

        return AccountResponse.builder()
            .id(savedAccount.getId())
            .accountNumber(savedAccount.getAccountNumber())
            .ownerName(savedAccount.getOwnerName())
            .currency(savedAccount.getCurrency())
            .balance(savedAccount.getBalance())
            .status(savedAccount.getStatus())
            .createdAt(savedAccount.getCreatedAt())
            .build();
    }

    @Cacheable(value = "account", key = "#id")
    @Transactional(readOnly = true)
    public AccountResponse getAccountById(Long id) {
        Account account = accountRepository.findById(id)
            .orElseThrow(() -> new AccountNotFoundException(String.valueOf(id)));
        return mapToResponse(account);
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse getAccountBalance(Long id) {
        AccountResponse account = getAccountById(id);
        return new AccountBalanceResponse(
            account.getId(),
            account.getBalance().setScale(2, RoundingMode.HALF_UP),
            account.getCurrency(),
            account.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public AccountStatementResponse getAccountTransactions(Long id, int page, int size) {
        if (page < 0 || size <= 0 || size > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ERR_REQ_003", "Invalid page or size parameter");
        }

        AccountResponse account = getAccountById(id);

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<LedgerEntry> entryPage = ledgerEntryRepository.findByAccountId(account.getId(), pageRequest);

        List<AccountStatementResponse.StatementItem> items = entryPage.getContent().stream()
            .map(entry -> new AccountStatementResponse.StatementItem(
                entry.getId(),
                entry.getEntryType(),
                entry.getAmount().setScale(2, RoundingMode.HALF_UP),
                entry.getBalanceAfter().setScale(2, RoundingMode.HALF_UP),
                entry.getTransfer() != null ? entry.getTransfer().getId() : null,
                entry.getCreatedAt()
            ))
            .toList();

        return new AccountStatementResponse(
            account.getId(),
            entryPage.getNumber(),
            entryPage.getSize(),
            entryPage.getTotalElements(),
            entryPage.getTotalPages(),
            items
        );
    }

    @CacheEvict(value = "account", key = "#id")
    @Transactional
    public Account updateAccountStatus(Long id, AccountStatus newStatus) {
        Account account = accountRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new AccountNotFoundException(String.valueOf(id)));

        if (newStatus == AccountStatus.CLOSED) {
            if (account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "ERR_ACC_002", "Cannot close account with remaining balance");
            }
        }

        account.setStatus(newStatus);
        return accountRepository.save(account);
    }

    @CacheEvict(value = "account", key = "#id")
    @Transactional
    public DepositResponse deposit(Long id, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_001", "Deposit amount must be greater than zero");
        }

        Account account = accountRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new AccountNotFoundException(String.valueOf(id)));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_003", "Account is not ACTIVE");
        }

        BigDecimal newBalance = account.getBalance().add(amount).setScale(2, RoundingMode.HALF_UP);
        account.setBalance(newBalance);
        accountRepository.save(account);

        LedgerEntry entry = LedgerEntry.builder()
            .account(account)
            .entryType(EntryType.CREDIT)
            .amount(amount.setScale(2, RoundingMode.HALF_UP))
            .balanceAfter(newBalance)
            .transfer(null)
            .build();
        entry = ledgerEntryRepository.save(entry);

        return new DepositResponse(account.getId(), newBalance, entry.getId());
    }

    @CacheEvict(value = "account", key = "#id")
    public WithdrawResponse withdraw(Long id, BigDecimal amount) {
        return lockService.executeWithAccountLock(id, () -> processWithdrawTransaction(id, amount));
    }

    @CacheEvict(value = "account", key = "#id")
    @Transactional
    public WithdrawResponse processWithdrawTransaction(Long id, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_001", "Withdrawal amount must be greater than zero");
        }

        Account account = accountRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new AccountNotFoundException(String.valueOf(id)));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_003", "Account is not ACTIVE");
        }

        if (account.getBalance().compareTo(amount) < 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_RULE_005", "Insufficient balance for withdrawal");
        }

        BigDecimal newBalance = account.getBalance().subtract(amount).setScale(2, RoundingMode.HALF_UP);
        account.setBalance(newBalance);
        accountRepository.save(account);

        LedgerEntry entry = LedgerEntry.builder()
            .account(account)
            .entryType(EntryType.DEBIT)
            .amount(amount.setScale(2, RoundingMode.HALF_UP))
            .balanceAfter(newBalance)
            .transfer(null)
            .build();
        entry = ledgerEntryRepository.save(entry);

        return new WithdrawResponse(account.getId(), newBalance, entry.getId());
    }

    public AccountResponse mapToResponse(Account account) {
        return new AccountResponse(
            account.getId(),
            account.getAccountNumber(),
            account.getOwnerName(),
            account.getCurrency(),
            account.getBalance().setScale(2, RoundingMode.HALF_UP),
            account.getStatus(),
            account.getCreatedAt()
        );
    }

}
