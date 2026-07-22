package com.easysubway.common.web;

import com.easysubway.admin.errors.application.service.ErrorEventRecorder;
import com.easysubway.common.error.ConflictException;
import com.easysubway.common.error.CorrelationId;
import com.easysubway.common.error.ErrorCode;
import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.common.error.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class CommonExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(CommonExceptionHandler.class);

	private final WebMessageResolver messages;
	private final ErrorEventRecorder errorEventRecorder;

	CommonExceptionHandler(WebMessageResolver messages) {
		this(messages, null);
	}

	@Autowired
	CommonExceptionHandler(WebMessageResolver messages, ErrorEventRecorder errorEventRecorder) {
		this.messages = messages;
		this.errorEventRecorder = errorEventRecorder;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(HttpServletRequest request) {
		return fail(request, ErrorCode.UNREADABLE_BODY, messages.message("common.error.unreadable-body"));
	}

	@ExceptionHandler(InvalidRequestException.class)
	ResponseEntity<ApiResponse<Void>> handleInvalidRequest(
		HttpServletRequest request,
		InvalidRequestException exception
	) {
		return fail(request, ErrorCode.INVALID_REQUEST, exception.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiResponse<Void>> handleInvalidRequestBody(
		HttpServletRequest request,
		MethodArgumentNotValidException exception
	) {
		String message = exception.getBindingResult().getFieldErrors().stream()
			.findFirst()
			.map(error -> error.getDefaultMessage())
			.orElseGet(() -> messages.message("common.error.invalid-body"));
		return fail(request, ErrorCode.INVALID_REQUEST, message);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ApiResponse<Void>> handleInvalidRequestParameter(HttpServletRequest request) {
		return fail(request, ErrorCode.INVALID_REQUEST, messages.message("common.error.invalid-parameter"));
	}

	@ExceptionHandler(ConflictException.class)
	ResponseEntity<ApiResponse<Void>> handleConflict(HttpServletRequest request, ConflictException exception) {
		return fail(request, ErrorCode.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	ResponseEntity<ApiResponse<Void>> handleResourceNotFound(
		HttpServletRequest request,
		ResourceNotFoundException exception
	) {
		return fail(request, ErrorCode.RESOURCE_NOT_FOUND, exception.getMessage());
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ApiResponse<Void>> handleNoResourceFound(HttpServletRequest request) {
		return fail(request, ErrorCode.RESOURCE_NOT_FOUND, messages.message("error.resource-not-found"));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Void> handleAccessDenied(HttpServletRequest request) {
		CorrelationId.currentOrCreate(request);
		return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Void> handleAuthentication(HttpServletRequest request) {
		CorrelationId.currentOrCreate(request);
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiResponse<Void>> handleUnhandled(HttpServletRequest request, Exception exception)
		throws Exception {
		if (shouldPropagate(exception)) {
			recordPropagatedServerError(request, exception);
			throw exception;
		}
		String correlationId = CorrelationId.currentOrCreate(request);
		log.error("unhandled exception correlationId={}", correlationId, exception);
		if (errorEventRecorder != null) {
			errorEventRecorder.recordIfNeeded(request, ErrorCode.INTERNAL_ERROR, exception, correlationId);
		}
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
			.body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, messages.message("error.internal-error"), correlationId));
	}

	private void recordPropagatedServerError(HttpServletRequest request, Exception exception) {
		if (errorEventRecorder == null || !isPropagatedServerError(exception)) {
			return;
		}
		String correlationId = CorrelationId.currentOrCreate(request);
		errorEventRecorder.recordIfNeeded(request, ErrorCode.INTERNAL_ERROR, exception, correlationId);
	}

	private static boolean isPropagatedServerError(Exception exception) {
		if (exception instanceof ConversionNotSupportedException
			|| exception instanceof HttpMessageNotWritableException) {
			return true;
		}
		// INTERNAL_ERROR(500)와 응답 status가 어긋나지 않도록 500만 기록한다.
		return exception instanceof ResponseStatusException responseStatusException
			&& responseStatusException.getStatusCode().value() == HttpStatus.INTERNAL_SERVER_ERROR.value();
	}

	private ResponseEntity<ApiResponse<Void>> fail(
		HttpServletRequest request,
		ErrorCode errorCode,
		String message
	) {
		String correlationId = CorrelationId.currentOrCreate(request);
		return ResponseEntity.status(errorCode.httpStatus())
			.body(ApiResponse.fail(errorCode, message, correlationId));
	}

	/**
	 * Spring MVC/client framework exceptions and {@link ResponseStatusException} keep their native
	 * HTTP status via remaining resolvers. Never wrap them as {@code INTERNAL_ERROR} 500.
	 */
	private static boolean shouldPropagate(Exception exception) {
		return exception instanceof HttpRequestMethodNotSupportedException
			|| exception instanceof HttpMediaTypeNotSupportedException
			|| exception instanceof HttpMediaTypeNotAcceptableException
			|| exception instanceof MissingServletRequestParameterException
			|| exception instanceof ServletRequestBindingException
			|| exception instanceof MethodArgumentNotValidException
			|| exception instanceof ConversionNotSupportedException
			|| exception instanceof TypeMismatchException
			|| exception instanceof HttpMessageNotWritableException
			|| exception instanceof NoHandlerFoundException
			|| exception instanceof ResponseStatusException;
	}
}
