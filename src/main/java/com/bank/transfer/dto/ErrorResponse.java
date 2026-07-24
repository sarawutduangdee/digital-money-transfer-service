package com.bank.transfer.dto;

public record ErrorResponse(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String traceId
) {
}
