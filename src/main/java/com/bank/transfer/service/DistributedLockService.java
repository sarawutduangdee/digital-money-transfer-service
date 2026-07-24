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
    private static final long WAIT_TIME = 15;
    private static final long LEASE_TIME = 5;


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
                throw new BusinessException(HttpStatus.CONFLICT, "ERR_SYS_004", "Account is busy: " + firstId);
            }

            secondAcquired = secondLock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);
            if (!secondAcquired) {
                throw new BusinessException(HttpStatus.CONFLICT, "ERR_SYS_004", "Account is busy: " + secondId);
            }

            return task.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "ERR_SYS_005", "Lock acquisition interrupted");
        } catch (Exception e) {
            if (e instanceof BusinessException) {
                throw e;
            }
            log.error("Redis failure during lock acquisition: {}", e.getMessage(), e);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ERR_SYS_006", "Service temporarily unavailable due to Redis error");
        } finally {
            if (secondAcquired && secondLock.isHeldByCurrentThread()) {
                secondLock.unlock();
            }
            if (firstAcquired && firstLock.isHeldByCurrentThread()) {
                firstLock.unlock();
            }
        }
    }

    public <T> T executeWithAccountLock(Long accountId, Supplier<T> task) {
        String lockKey = LOCK_KEY_PREFIX + accountId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;

        try {
            acquired = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException(HttpStatus.CONFLICT, "ERR_SYS_004", "Account is busy: " + accountId);
            }
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "ERR_SYS_005", "Lock acquisition interrupted");
        } catch (Exception e) {
            if (e instanceof BusinessException) {
                throw e;
            }
            log.error("Redis failure during lock acquisition for account {}: {}", accountId, e.getMessage(), e);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ERR_SYS_006", "Service temporarily unavailable due to Redis error");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
