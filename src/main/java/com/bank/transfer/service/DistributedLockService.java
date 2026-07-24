package com.bank.transfer.service;

import com.bank.transfer.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY_PREFIX = "lock:account:";
    private static final long WAIT_TIME = 3;
    private static final long LEASE_TIME = 5;


    public <T> T executeWithAccountLock(Long accountId, Supplier<T> task) {
        String lockKey = LOCK_KEY_PREFIX + accountId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean isAcquired = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);

            if (!isAcquired) {
                log.warn("Failed to acquire distributed lock for key: {}", lockKey);
                throw new BusinessException(HttpStatus.CONFLICT, "ERR_SYS_004", "System is busy, please try again");
            }

            return task.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "ERR_SYS_005", "Lock acquisition interrupted");
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("Redis connection error during lock acquisition: {}", e.getMessage(), e);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ERR_SYS_006", "Transaction failed due to locking system error");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public <T> T executeWithTwoAccountsLock(Long fromAccountId, Long toAccountId, Supplier<T> task) {
        Long firstId = Math.min(fromAccountId, toAccountId);
        Long secondId = Math.max(fromAccountId, toAccountId);

        String firstLockKey = LOCK_KEY_PREFIX + firstId;
        String secondLockKey = LOCK_KEY_PREFIX + secondId;

        RLock firstLock = redissonClient.getLock(firstLockKey);
        RLock secondLock = redissonClient.getLock(secondLockKey);

        boolean firstAcquired = false;
        boolean secondAcquired = false;

        try {
            firstAcquired = firstLock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);
            if (!firstAcquired) {
                throw new BusinessException(HttpStatus.CONFLICT, "ERR_SYS_004", "System is busy on account: " + firstId);
            }

            secondAcquired = secondLock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);
            if (!secondAcquired) {
                throw new BusinessException(HttpStatus.CONFLICT, "ERR_SYS_004", "System is busy on account: " + secondId);
            }

            return task.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "ERR_SYS_005", "Lock acquisition interrupted");
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("Redis lock acquisition failed: {}", e.getMessage(), e);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ERR_SYS_006", "Transaction failed due to locking system error");
        } finally {
            if (secondAcquired && secondLock.isHeldByCurrentThread()) {
                secondLock.unlock();
            }
            if (firstAcquired && firstLock.isHeldByCurrentThread()) {
                firstLock.unlock();
            }
        }
    }
}
