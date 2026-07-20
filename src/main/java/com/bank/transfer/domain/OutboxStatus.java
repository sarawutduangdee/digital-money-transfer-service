package com.bank.transfer.domain;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
