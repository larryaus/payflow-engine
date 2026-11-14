package com.payflow.account.exception;

/** 金额非法 (null 或 ≤ 0), 映射为 400 INVALID_AMOUNT */
public class InvalidAmountException extends RuntimeException {

    public InvalidAmountException(String message) {
        super(message);
    }
}
