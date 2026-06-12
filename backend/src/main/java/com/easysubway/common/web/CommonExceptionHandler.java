package com.easysubway.common.web;

import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.common.error.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class CommonExceptionHandler {

	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ApiResponse<Void> handleUnreadableMessage() {
		return ApiResponse.fail("요청 본문을 확인해야 합니다.");
	}

	@ExceptionHandler(InvalidRequestException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ApiResponse<Void> handleInvalidRequest(InvalidRequestException exception) {
		return ApiResponse.fail(exception.getMessage());
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	ApiResponse<Void> handleResourceNotFound(ResourceNotFoundException exception) {
		return ApiResponse.fail(exception.getMessage());
	}
}
