package com.easysubway.admin.errors.adapter.in.web;

import com.easysubway.admin.errors.application.ErrorEventQuery;
import com.easysubway.admin.errors.application.port.out.ErrorEventRepository;
import com.easysubway.admin.errors.domain.ErrorEvent;
import com.easysubway.admin.navigation.AdminProgram;
import com.easysubway.common.domain.PageResult;
import com.easysubway.common.web.pagination.AdminPageRequest;
import com.easysubway.common.web.pagination.EgovPaginationView;
import com.easysubway.common.error.ErrorCategory;
import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxRequest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class ErrorEventAdminPageController {

	private static final String PAGE_PATH = "/admin/errors/page";
	private static final int DEFAULT_LOOKBACK_DAYS = 7;

	private final ErrorEventRepository errorEventRepository;

	ErrorEventAdminPageController(ErrorEventRepository errorEventRepository) {
		this.errorEventRepository = errorEventRepository;
	}

	@GetMapping(PAGE_PATH)
	@PreAuthorize("hasAuthority('admin.errors.read')")
	String page(
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(required = false) String code,
		@RequestParam(required = false) String category,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		populate(model, from, to, code, category, page, size);
		return "admin/errors/list";
	}

	@HxRequest
	@GetMapping(PAGE_PATH)
	@PreAuthorize("hasAuthority('admin.errors.read')")
	String fragment(
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(required = false) String code,
		@RequestParam(required = false) String category,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		@RequestHeader(value = "HX-History-Restore-Request", required = false) boolean historyRestore,
		Model model
	) {
		populate(model, from, to, code, category, page, size);
		return historyRestore ? "admin/errors/list" : "admin/errors/list :: errorResults";
	}

	private void populate(
		Model model,
		LocalDate from,
		LocalDate to,
		String code,
		String category,
		Integer page,
		Integer size
	) {
		LocalDate effectiveTo = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
		LocalDate effectiveFrom = from == null ? effectiveTo.minusDays(DEFAULT_LOOKBACK_DAYS) : from;
		AdminPageRequest pageRequest = AdminPageRequest.of(page, size);
		ErrorEventQuery query = ErrorEventQuery.of(
			effectiveFrom.atStartOfDay().toInstant(ZoneOffset.UTC),
			effectiveTo.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC),
			code,
			category,
			pageRequest.page(),
			pageRequest.size()
		);
		long total = errorEventRepository.count(query);
		EgovPaginationView pageView = EgovPaginationView.from(
			pageRequest.page(),
			pageRequest.size(),
			total
		);
		ErrorEventQuery pageQuery = query.withPage(pageView.page());
		PageResult<ErrorEvent> result = errorEventRepository.search(pageQuery);

		model.addAttribute("title", "오류 이벤트");
		model.addAttribute("activeProgram", AdminProgram.ERROR_EVENTS.id());
		model.addAttribute("basePath", PAGE_PATH);
		model.addAttribute("from", effectiveFrom.toString());
		model.addAttribute("to", effectiveTo.toString());
		model.addAttribute("selectedCode", code == null ? "" : code);
		model.addAttribute("selectedCategory", category == null ? "" : category);
		model.addAttribute("categoryOptions", categoryOptions(category));
		model.addAttribute("events", result.items());
		model.addAttribute("total", total);
		model.addAttribute("page", pageView);
		model.addAttribute("paginationLabel", "오류 이벤트 페이지");
		model.addAttribute("paginationLinks", pageView.links(PAGE_PATH, filterParams(pageQuery, effectiveFrom, effectiveTo)));
	}

	private static List<Map<String, Object>> categoryOptions(String selected) {
		return List.of(
			option("", "카테고리 전체", selected == null || selected.isBlank()),
			option(ErrorCategory.SYSTEM.name(), "SYSTEM", ErrorCategory.SYSTEM.name().equals(selected)),
			option(ErrorCategory.DEPENDENCY.name(), "DEPENDENCY", ErrorCategory.DEPENDENCY.name().equals(selected))
		);
	}

	private static Map<String, Object> option(String value, String label, boolean selected) {
		Map<String, Object> option = new LinkedHashMap<>();
		option.put("value", value);
		option.put("label", label);
		option.put("selected", selected);
		return option;
	}

	private static Map<String, String> filterParams(ErrorEventQuery query, LocalDate from, LocalDate to) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("from", from.toString());
		params.put("to", to.toString());
		if (query.code() != null) {
			params.put("code", query.code());
		}
		if (query.category() != null) {
			params.put("category", query.category());
		}
		return params;
	}
}
