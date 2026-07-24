package com.bank.transfer.dto;

import java.io.Serializable;

public record IdempotencyRecord(
    String requestHash,
    TransferResponse response
) implements Serializable {
}
