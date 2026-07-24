package com.bank.transfer.integration;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.AccountStatus;
import com.bank.transfer.dto.TransferRequest;
import com.bank.transfer.dto.TransferResponse;
import com.bank.transfer.repository.AccountRepository;
import com.bank.transfer.repository.LedgerEntryRepository;
import com.bank.transfer.repository.OutboxEventRepository;
import com.bank.transfer.repository.TransferRepository;
import com.bank.transfer.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
@SpringBootTest
@ActiveProfiles("test")
class TransferConcurrencyIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CacheManager cacheManager; // 📌 เพิ่มตัวจัดการ Cache

    private Long acc1Id;
    private Long acc2Id;

    @BeforeEach
    void setUp() {
        // 1. เคลียร์ Redis ทั้งหมด
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();

        // 2. เคลียร์ Spring Cache เฉพาะ Account
        if (cacheManager.getCache("account") != null) {
            cacheManager.getCache("account").clear();
        }

        // 3. ลบตารางลูก -> ตารางแม่
        ledgerEntryRepository.deleteAll();
        outboxEventRepository.deleteAll();
        transferRepository.deleteAll();
        accountRepository.deleteAll();

        Account acc1 = accountRepository.save(Account.builder()
            .accountNumber("ACC-TEST-001")
            .ownerName("User A")
            .balance(new BigDecimal("1000.00"))
            .currency("THB")
            .status(AccountStatus.ACTIVE)
            .build());

        Account acc2 = accountRepository.save(Account.builder()
            .accountNumber("ACC-TEST-002")
            .ownerName("User B")
            .balance(new BigDecimal("0.00"))
            .currency("THB")
            .status(AccountStatus.ACTIVE)
            .build());

        acc1Id = acc1.getId();
        acc2Id = acc2.getId();
    }

    @Test
    @DisplayName("Concurrency Test: ยิงโอนพร้อมกัน 10 Threads (100x10) ยอดรวมต้องถูกต้อง ไม่ติดลบ")
    void testConcurrentTransfers_shouldBeConsistent() throws InterruptedException {
        int threadCount = 10;
        BigDecimal transferAmount = new BigDecimal("100.00");

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String idempotencyKey = "concurrent-key-" + i;
            executorService.submit(() -> {
                try {
                    TransferRequest request = new TransferRequest("ACC-TEST-001", "ACC-TEST-002", transferAmount, "THB");
                    transferService.processTransfer(idempotencyKey, request);
                } catch (Exception e) {
                    System.err.println("Transfer failed for key " + idempotencyKey + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // 📌 เคลียร์ Spring Cache ใหม่อีกครั้งก่อนตรวจสอบยอดเงิน
        if (cacheManager.getCache("account") != null) {
            cacheManager.getCache("account").clear();
        }

        // ดึงจาก DB ซึ่งจะได้ยอดสดๆ ที่ไม่มี Cache กั้นแล้ว
        Account acc1Updated = accountRepository.findById(acc1Id).orElseThrow();
        Account acc2Updated = accountRepository.findById(acc2Id).orElseThrow();

        assertThat(acc1Updated.getBalance()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(acc2Updated.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("Idempotency Test: ยิง Idempotency-Key เดิมซ้ำ 5 ครั้ง ยอดต้องตัดแค่ครั้งเดียว")
    void testIdempotentTransfers_shouldExecuteOnlyOnce() throws InterruptedException {
        int times = 5;
        String sameKey = "duplicate-key-999";
        BigDecimal amount = new BigDecimal("200.00");

        ExecutorService executorService = Executors.newFixedThreadPool(times);
        CountDownLatch latch = new CountDownLatch(times);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < times; i++) {
            executorService.submit(() -> {
                try {
                    TransferRequest request = new TransferRequest("ACC-TEST-001", "ACC-TEST-002", amount, "THB");
                    TransferResponse res = transferService.processTransfer(sameKey, request);
                    if (res != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("Idempotency Transfer failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // 📌 เคลียร์ Cache อีกเช่นกัน
        if (cacheManager.getCache("account") != null) {
            cacheManager.getCache("account").clear();
        }

        Account acc1Updated = accountRepository.findById(acc1Id).orElseThrow();

        assertThat(acc1Updated.getBalance()).isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(transferRepository.count()).isEqualTo(1);
    }
}
