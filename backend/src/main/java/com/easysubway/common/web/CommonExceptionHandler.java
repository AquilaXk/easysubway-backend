package com.easysubway.common.web;

import com.easysubway.common.error.ConflictException;
import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.common.error.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class CommonExceptionHandler {

	private final WebMessageResolver messages;

	CommonExceptionHandler(WebMessageResolver messages) {
		this.messages = messages;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ApiResponse<Void> handleUnreadableMessage() {
		return ApiResponse.fail(messages.message("common.error.unreadable-body"));
	}

	@ExceptionHandler(InvalidRequestException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ApiResponse<Void> handleInvalidRequest(InvalidRequestException exception) {
		return ApiResponse.fail(exception.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ApiResponse<Void> handleInvalidRequestBody(MethodArgumentNotValidException exception) {
		String message = exception.getBindingResult().getFieldErrors().stream()
			.findFirst()
			.map(error -> error.getDefaultMessage())
			.orElseGet(() -> messages.message("common.error.invalid-body"));
		return ApiResponse.fail(message);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ApiResponse<Void> handleInvalidRequestParameter() {
		return ApiResponse.fail(messages.message("common.error.invalid-parameter"));
	}

	@ExceptionHandler(ConflictException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	ApiResponse<Void> handleConflict(ConflictException exception) {
		return ApiResponse.fail(exception.getMessage());
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	ApiResponse<Void> handleResourceNotFound(ResourceNotFoundException exception) {
		return ApiResponse.fail(exception.getMessage());
	}
}
