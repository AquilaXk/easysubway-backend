package com.easysubway.common.web;

import com.easysubway.common.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, String message, String code, String correlationId) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null, null, null);
	}

	@Deprecated
	public static <T> ApiResponse<T> fail(String message) {
		return new ApiResponse<>(false, null, message, null, null);
	}

	public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, String correlationId) {
		return new ApiResponse<>(false, null, message, errorCode.code(), correlationId);
	}
}
