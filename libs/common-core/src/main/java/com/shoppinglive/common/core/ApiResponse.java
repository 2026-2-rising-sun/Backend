package com.shoppinglive.common.core;

public record ApiResponse<T>(boolean success, T data, ApiError error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode code, String message, String requestId) {
        return new ApiResponse<>(false, null, new ApiError(code.name(), message, requestId));
    }

    public record ApiError(String code, String message, String requestId) {
    }
}
