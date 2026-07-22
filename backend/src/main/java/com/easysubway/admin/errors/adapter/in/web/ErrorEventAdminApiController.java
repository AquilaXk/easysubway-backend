package com.easysubway.admin.errors.adapter.in.web;

import com.easysubway.admin.errors.application.ErrorEventQuery;
import com.easysubway.admin.errors.application.port.out.ErrorEventRepository;
import com.easysubway.admin.errors.domain.ErrorEvent;
import com.easysubway.common.domain.PageResult;
import com.easysubway.common.web.ApiResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ErrorEventAdminApiController {

	private final ErrorEventRepository errorEventRepository;

	ErrorEventAdminApiController(ErrorEventRepository errorEventRepository) {
		this.errorEventRepository = errorEventRepository;
	}

	@GetMapping("/admin/api/errors")
	@PreAuthorize("hasAuthority('admin.errors.read')")
	ApiResponse<PageResponse> list(
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(required = false) String code,
		@RequestParam(required = false) String category,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size
	) {
		ErrorEventQuery query = ErrorEventQuery.of(
			toInstant(from),
			toExclusiveInstant(to),
			code,
			category,
			page,
			size
		);
		PageResult<ErrorEvent> result = errorEventRepository.search(query);
		return ApiResponse.ok(PageResponse.from(result));
	}

	private static Instant toInstant(LocalDate date) {
		return date == null ? null : date.atStartOfDay().toInstant(ZoneOffset.UTC);
	}

	private static Instant toExclusiveInstant(LocalDate date) {
		return date == null ? null : date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
	}

	record PageResponse(List<ErrorEventView> items, int page, int size, boolean hasNext) {

		static PageResponse from(PageResult<ErrorEvent> page) {
			return new PageResponse(
				page.items().stream().map(ErrorEventView::from).toList(),
				page.page(),
				page.size(),
				page.hasNext()
			);
		}
	}

	record ErrorEventView(
		long id,
		Instant firstOccurredAt,
		Instant lastOccurredAt,
		String code,
		String category,
		int httpStatus,
		String method,
		String pathPattern,
		String exceptionClass,
		String stackHash,
		String sampleCorrelationId,
		long occurrenceCount
	) {

		static ErrorEventView from(ErrorEvent event) {
			return new ErrorEventView(
				event.id(),
				event.firstOccurredAt(),
				event.lastOccurredAt(),
				event.code(),
				event.category(),
				event.httpStatus(),
				event.method(),
				event.pathPattern(),
				event.exceptionClass(),
				event.stackHash(),
				event.sampleCorrelationId(),
				event.occurrenceCount()
			);
		}
	}
}
