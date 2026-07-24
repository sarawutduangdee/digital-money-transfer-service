package com.bank.transfer.controller;

import com.bank.transfer.domain.Account;
import com.bank.transfer.dto.AccountBalanceResponse;
import com.bank.transfer.dto.AccountCreateRequest;
import com.bank.transfer.dto.AccountResponse;
import com.bank.transfer.dto.AccountStatementResponse;
import com.bank.transfer.dto.DepositRequest;
import com.bank.transfer.dto.DepositResponse;
import com.bank.transfer.dto.UpdateStatusRequest;
import com.bank.transfer.dto.WithdrawRequest;
import com.bank.transfer.dto.WithdrawResponse;
import com.bank.transfer.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody AccountCreateRequest request) {
        AccountResponse response = accountService.createAccount(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(response.getId())
            .toUri();

        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable Long id) {
        Account account = accountService.getAccountById(id);
        return ResponseEntity.ok(accountService.mapToResponse(account));
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<AccountBalanceResponse> getAccountBalance(@PathVariable Long id) {
        return ResponseEntity.ok(accountService.getAccountBalance(id));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<AccountStatementResponse> getAccountTransactions(
        @PathVariable Long id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(accountService.getAccountTransactions(id, page, size));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<AccountResponse> updateStatus(
        @PathVariable Long id,
        @Valid @RequestBody UpdateStatusRequest request) {
        Account account = accountService.updateAccountStatus(id, request.status());
        return ResponseEntity.ok(accountService.mapToResponse(account));
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<DepositResponse> deposit(
        @PathVariable Long id,
        @Valid @RequestBody DepositRequest request) {
        return ResponseEntity.ok(accountService.deposit(id, request.amount()));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<WithdrawResponse> withdraw(
        @PathVariable Long id,
        @Valid @RequestBody WithdrawRequest request) {
        return ResponseEntity.ok(accountService.withdraw(id, request.amount()));
    }
}
