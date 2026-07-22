package com.easysubway.admin.web;

import com.easysubway.admin.errors.application.service.ErrorEventRecorder;
import com.easysubway.common.error.ConflictException;
import com.easysubway.common.error.CorrelationId;
import com.easysubway.common.error.ErrorCode;
import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.common.error.ResourceNotFoundException;
import com.easysubway.transit.domain.MasterDataWriteNotAllowedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

@Component
class AdminHtmlExceptionResolver implements HandlerExceptionResolver, Ordered {

	private final ErrorEventRecorder errorEventRecorder;

	AdminHtmlExceptionResolver(ErrorEventRecorder errorEventRecorder) {
		this.errorEventRecorder = errorEventRecorder;
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public ModelAndView resolveException(
		HttpServletRequest request,
		HttpServletResponse response,
		Object handler,
		Exception exception
	) {
		if (!AdminHtmlRequest.matches(request)) {
			return null;
		}
		AdminHtmlError error = AdminHtmlError.from(exception);
		// ErrorCode 와이어 계약상 일반 시스템 장애는 INTERNAL_ERROR(500)만 기록한다.
		if (error.status() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
			String correlationId = CorrelationId.currentOrCreate(request);
			errorEventRecorder.recordIfNeeded(request, ErrorCode.INTERNAL_ERROR, exception, correlationId);
		}
		response.setStatus(error.status());
		ModelAndView view = new ModelAndView("admin/error");
		view.addObject("status", error.status());
		view.addObject("title", error.title());
		view.addObject("message", error.message());
		view.addObject("detail", error.detail());
		return view;
	}

	private record AdminHtmlError(int status, String title, String message, String detail) {

		static AdminHtmlError from(Exception exception) {
			return switch (exception) {
				case MasterDataWriteNotAllowedException error -> new AdminHtmlError(
					HttpStatus.LOCKED.value(),
					"읽기 전용 마스터 데이터",
					"현재 운영 마스터 데이터는 저장할 수 없습니다.",
					error.getMessage()
				);
				case ResourceNotFoundException error -> new AdminHtmlError(
					HttpStatus.NOT_FOUND.value(),
					"대상을 찾을 수 없습니다",
					error.getMessage(),
					"목록으로 돌아가 최신 상태를 다시 확인해 주세요."
				);
				case ConflictException error -> new AdminHtmlError(
					HttpStatus.CONFLICT.value(),
					"요청이 최신 상태와 충돌했습니다",
					error.getMessage(),
					"화면을 새로고침한 뒤 다시 시도해 주세요."
				);
				case InvalidRequestException error -> new AdminHtmlError(
					HttpStatus.BAD_REQUEST.value(),
					"입력값을 확인해야 합니다",
					error.getMessage(),
					"입력한 값을 확인한 뒤 다시 제출해 주세요."
				);
				case MethodArgumentTypeMismatchException error -> new AdminHtmlError(
					HttpStatus.BAD_REQUEST.value(),
					"입력값을 확인해야 합니다",
					error.getMessage(),
					"입력한 값을 확인한 뒤 다시 제출해 주세요."
				);
				case ErrorResponse errorResponse -> new AdminHtmlError(
					errorResponse.getStatusCode().value(),
					errorTitle(errorResponse.getStatusCode().value()),
					"관리자 요청 형식이 올바르지 않습니다.",
					exception.getMessage()
				);
				default -> new AdminHtmlError(
					HttpStatus.INTERNAL_SERVER_ERROR.value(),
					"요청을 처리하지 못했습니다",
					"관리자 요청 처리 중 오류가 발생했습니다.",
					"잠시 후 다시 시도하고, 문제가 반복되면 운영 로그를 확인해 주세요."
				);
			};
		}

		private static String errorTitle(int status) {
			return switch (status) {
				case 400 -> "입력값을 확인해야 합니다";
				case 405 -> "허용되지 않는 요청입니다";
				case 415 -> "지원하지 않는 요청 형식입니다";
				default -> "요청을 처리할 수 없습니다";
			};
		}
	}
}
