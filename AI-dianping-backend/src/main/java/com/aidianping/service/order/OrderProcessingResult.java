package com.aidianping.service.order;

public enum OrderProcessingResult {
    CREATED,
    ALREADY_EXISTS,
    RETRY_LATER,
    OUT_OF_STOCK,
    DUPLICATE_CONFLICT,
    INVALID_MESSAGE
}
