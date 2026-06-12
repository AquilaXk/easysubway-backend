package com.easysubway.common.web;

import com.easysubway.common.error.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class CommonExceptionHandler {

	@ExceptionHandler(ResourceNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	ApiResponse<Void> handleResourceNotFound(ResourceNotFoundException exception) {
		return ApiResponse.fail(exception.getMessage());
	}
}
