package com.easysubway.route.adapter.in.web;

import com.easysubway.common.error.CorrelationId;
import com.easysubway.common.error.ErrorCode;
import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.route.application.service.ItxTimetableUnavailableException;
import com.easysubway.route.application.service.RouteSessionAttestationRejectedException;
import com.easysubway.route.application.service.RouteSessionAttestationUnavailableException;
import com.easysubway.transit.domain.StationNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@Profile("prod | staging | release | prod-like")
@RestControllerAdvice(assignableTypes = {RouteV2SessionController.class, RouteSearchController.class})
class RouteV2ExceptionHandler {
	private final RouteV2Metrics metrics;

	RouteV2ExceptionHandler() {
		this(RouteV2Metrics.noop());
	}

	@Autowired
	RouteV2ExceptionHandler(RouteV2Metrics metrics) {
		this.metrics = metrics;
	}

	@ExceptionHandler(RouteSessionAttestationRejectedException.class)
	ResponseEntity<RouteV2Error> handleAttestationRejected(HttpServletRequest request) {
		return error(
			request,
			HttpStatus.FORBIDDEN,
			ErrorCode.ROUTE_SESSION_ATTESTATION_REJECTED,
			"ITX 시간표를 불러올 수 없어요"
		);
	}

	@ExceptionHandler(RouteSessionAttestationUnavailableException.class)
	ResponseEntity<RouteV2Error> handleAttestationUnavailable(HttpServletRequest request) {
		return error(
			request,
			HttpStatus.SERVICE_UNAVAILABLE,
			ErrorCode.ROUTE_SESSION_ATTESTATION_UNAVAILABLE,
			"ITX 시간표를 불러올 수 없어요"
		);
	}

	@ExceptionHandler(ItxTimetableUnavailableException.class)
	ResponseEntity<RouteV2Error> handleTimetableUnavailable(HttpServletRequest request) {
		return error(
			request,
			HttpStatus.SERVICE_UNAVAILABLE,
			ErrorCode.ITX_TIMETABLE_UNAVAILABLE,
			"ITX 시간표를 불러올 수 없어요"
		);
	}

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HttpMessageNotReadableException.class,
		InvalidRequestException.class,
		StationNotFoundException.class
	})
	ResponseEntity<RouteV2Error> handleInvalidRequest(HttpServletRequest request) {
		if ("/api/v2/routes/session".equals(request.getRequestURI())) {
			return handleAttestationRejected(request);
		}
		return error(request, HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.ROUTE_SCOPE_INVALID, "지원하지 않는 경로예요");
	}

	private ResponseEntity<RouteV2Error> error(
		HttpServletRequest request,
		HttpStatus status,
		ErrorCode errorCode,
		String message
	) {
		String correlationId = CorrelationId.currentOrCreate(request);
		metrics.recordResponse(status.value(), errorCode.code());
		return ResponseEntity.status(status)
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.header(CorrelationId.HEADER, correlationId)
			.body(new RouteV2Error(false, errorCode.code(), message, correlationId));
	}

	private record RouteV2Error(boolean success, String code, String message, String correlationId) {
	}
}
