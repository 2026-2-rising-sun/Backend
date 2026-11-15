package com.shoppinglive.payment.payment.dto;

/** 200 {@code APPROVED}, 202 {@code PENDING}. */
public record ApprovalResponse(long paymentId, String status) {}
