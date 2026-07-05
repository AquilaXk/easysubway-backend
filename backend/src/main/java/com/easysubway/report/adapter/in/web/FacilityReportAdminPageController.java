package com.easysubway.report.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.savedview.application.port.in.AdminSavedViewUseCase;
import com.easysubway.admin.savedview.domain.AdminSavedView;
import com.easysubway.admin.web.AdminFormErrorView;
import com.easysubway.admin.web.AdminMasterLabelResolver;
import com.easysubway.common.domain.PageResult;
import com.easysubway.common.web.WebMessageResolver;
import com.easysubway.common.web.pagination.EgovPaginationView;
import com.easysubway.report.application.port.in.FacilityReportListQuery;
import com.easysubway.report.application.port.in.FacilityReportPageRequest;
import com.easysubway.report.application.port.out.LoadFacilityReportPhotoPort;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.ReportProcessingTimeSummary;
import com.easysubway.transit.domain.MasterDataWriteNotAllowedException;
import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxRequest;
import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxTrigger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
class FacilityReportAdminPageController {

	private static final int REPORT_SURGE_ALERT_THRESHOLD = 10;
	private static final long REPORT_SURGE_LOOKBACK_HOURS = 24;
	private static final String REPORTS_PROGRAM_ID = "a-reports";

	private final FacilityReportUseCase facilityReportUseCase;
	private final LoadFacilityReportPhotoPort loadFacilityReportPhotoPort;
	private final WebMessageResolver messages;
	private final AdminAuditWriter auditWriter;
	private final AdminMasterLabelResolver labelResolver;
	private final AdminSavedViewUseCase savedViewUseCase;
	private final Clock clock;

	@Autowired
	FacilityReportAdminPageController(
		FacilityReportUseCase facilityReportUseCase,
		LoadFacilityReportPhotoPort loadFacilityReportPhotoPort,
		WebMessageResolver messages,
		AdminAuditWriter auditWriter,
		AdminMasterLabelResolver labelResolver,
		AdminSavedViewUseCase savedViewUseCase,
		ObjectProvider<Clock> clockProvider
	) {
		this(
			facilityReportUseCase,
			loadFacilityReportPhotoPort,
			messages,
			auditWriter,
			labelResolver,
			savedViewUseCase,
			clockProvider.getIfAvailable(Clock::systemDefaultZone)
		);
	}

	FacilityReportAdminPageController(FacilityReportUseCase facilityReportUseCase, Clock clock) {
		this(facilityReportUseCase, objectKey -> java.util.Optional.empty(), WebMessageResolver.defaultMessages(), clock);
	}

	FacilityReportAdminPageController(
		FacilityReportUseCase facilityReportUseCase,
		LoadFacilityReportPhotoPort loadFacilityReportPhotoPort,
		WebMessageResolver messages,
		Clock clock
	) {
		this(
			facilityReportUseCase,
			loadFacilityReportPhotoPort,
			messages,
			AdminAuditWriter.noop(),
			AdminMasterLabelResolver.empty(),
			AdminSavedViewUseCase.readOnlyEmpty(),
			clock
		);
	}

	FacilityReportAdminPageController(
		FacilityReportUseCase facilityReportUseCase,
		LoadFacilityReportPhotoPort loadFacilityReportPhotoPort,
		WebMessageResolver messages,
		AdminAuditWriter auditWriter,
		AdminMasterLabelResolver labelResolver,
		AdminSavedViewUseCase savedViewUseCase,
		Clock clock
	) {
		this.facilityReportUseCase = facilityReportUseCase;
		this.loadFacilityReportPhotoPort = loadFacilityReportPhotoPort;
		this.messages = messages;
		this.auditWriter = auditWriter;
		this.labelResolver = labelResolver;
		this.savedViewUseCase = savedViewUseCase;
		this.clock = clock;
	}

	@GetMapping("/admin/reports/page")
	String reportListPage(
		@RequestParam(required = false) FacilityReportStatus status,
		@RequestParam(required = false) String keyword,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(required = false) String sort,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Authentication authentication,
		Model model
	) {
		// 기본 저장 뷰 자동 적용: 필터 없이 목록에 진입하면 사용자의 기본 뷰 질의로 리다이렉트한다.
		String defaultViewUrl = defaultViewRedirect(status, keyword, from, to, sort, page, size, authentication);
		if (defaultViewUrl != null) {
			return "redirect:" + defaultViewUrl;
		}
		FacilityReportListQuery query = FacilityReportListQuery.of(status, keyword, from, to, sort, page, size);
		EgovPaginationView pageView = reportListPageView(query);
		if (pageView.page() != query.page() || pageView.size() != query.size()) {
			return redirectToReportList(query, pageView);
		}
		addReportResultsAttributes(query, pageView, authentication, model);
		addReportSummaryAttributes(model);
		return "admin/reports/list";
	}

	// 파라미터 없는 fresh 진입 + 기본 뷰(비어있지 않은 질의) 존재 시 그 질의 URL을 반환한다.
	// 리다이렉트 후에는 파라미터가 붙어 fresh가 아니므로 재리다이렉트 루프가 없다.
	private String defaultViewRedirect(
		FacilityReportStatus status,
		String keyword,
		LocalDate from,
		LocalDate to,
		String sort,
		Integer page,
		Integer size,
		Authentication authentication
	) {
		boolean freshEntry = status == null && keyword == null && from == null && to == null
			&& sort == null && page == null && size == null;
		if (!freshEntry || authentication == null) {
			return null;
		}
		return savedViewUseCase.findDefaultView(authentication.getName(), REPORTS_PROGRAM_ID)
			.map(AdminSavedView::queryParams)
			.filter(queryParams -> queryParams != null && !queryParams.isBlank())
			.map(queryParams -> "/admin/reports/page?" + queryParams)
			.orElse(null);
	}

	// 진화형 향상 파일럿(#1736): 같은 URL·같은 모델을 htmx 부분 응답으로 반환한다.
	// HX-Request 헤더가 있으면 검색·필터 툴바·표·페이지네이션만 담은 reportResults fragment만 렌더한다.
	// 부분 응답은 전체 새로고침 리다이렉트 대신 클램프된 페이지로 즉시 렌더해 htmx swap이 끊기지 않게 한다.
	// 단, htmx 히스토리 복원 요청(스냅샷 캐시 부재)은 fragment가 아니라 셸까지 포함한 풀페이지를 돌려줘야
	// body에 부분 응답만 스왑돼 화면이 깨지는 것을 막는다.
	@HxRequest
	@GetMapping("/admin/reports/page")
	String reportListFragment(
		@RequestParam(required = false) FacilityReportStatus status,
		@RequestParam(required = false) String keyword,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(required = false) String sort,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		@RequestHeader(value = "HX-History-Restore-Request", required = false) boolean historyRestore,
		Authentication authentication,
		Model model
	) {
		if (historyRestore) {
			return reportListPage(status, keyword, from, to, sort, page, size, authentication, model);
		}
		FacilityReportListQuery query = FacilityReportListQuery.of(status, keyword, from, to, sort, page, size);
		EgovPaginationView pageView = reportListPageView(query);
		addReportResultsAttributes(clampQuery(query, pageView), pageView, authentication, model);
		return "admin/reports/list :: reportResults";
	}

	private EgovPaginationView reportListPageView(FacilityReportListQuery query) {
		return EgovPaginationView.from(query.page(), query.size(), facilityReportUseCase.countReports(query));
	}

	private static FacilityReportListQuery clampQuery(FacilityReportListQuery query, EgovPaginationView pageView) {
		return new FacilityReportListQuery(
			query.status(),
			query.keyword(),
			query.createdFrom(),
			query.createdTo(),
			query.sortField(),
			query.sortDirection(),
			pageView.page(),
			pageView.size()
		);
	}

	// reportResults fragment에 담기는 속성만 채운다(검색·필터 툴바·표·페이지네이션·저장된 뷰).
	private void addReportResultsAttributes(
		FacilityReportListQuery query,
		EgovPaginationView pageView,
		Authentication authentication,
		Model model
	) {
		PageResult<FacilityReportSummary> reportPage = facilityReportUseCase.searchReportSummaries(query);
		List<FacilityReportSummary> items = reportPage.items();
		// 원시 ID 단독 노출 금지(#1737): 페이지의 역·시설 ID를 모아 한 번에 "이름(코드)"로 해석한다.
		Map<String, String> stationLabels = labelResolver.stationLabels(
			items.stream().map(FacilityReportSummary::stationId).toList());
		Map<String, String> facilityLabels = labelResolver.facilityLabels(
			items.stream().map(FacilityReportSummary::facilityId).toList());
		List<FacilityReportListPageRow> reports = items.stream()
			.map(report -> FacilityReportListPageRow.from(report, messages, stationLabels, facilityLabels))
			.toList();

		model.addAttribute("reports", reports);
		model.addAttribute("page", pageView);
		model.addAttribute("paginationLinks", pageView.links("/admin/reports/page", queryParams(query)));
		model.addAttribute("selectedStatus", query.status());
		model.addAttribute("statusOptions", statusOptions());
		model.addAttribute("searchKeyword", query.keyword());
		model.addAttribute("createdFrom", query.createdFrom());
		model.addAttribute("createdTo", query.createdTo());
		model.addAttribute("reportQuery", query);
		// 기본 정렬(최신순)일 때는 URL을 깨끗이 두기 위해 sort 파라미터를 생략한다.
		Object sortParam = queryParams(query).get("sort");
		model.addAttribute("sortParam", sortParam);

		// 링크는 컨트롤러에서 조립한다: Thymeleaf @{...(p=${null})}은 null도 빈 파라미터로 렌더해 URL을 오염시킨다.
		String currentSort = sortParam == null ? null : sortParam.toString();
		String keyword = query.keyword();
		LocalDate createdFromDate = query.createdFrom();
		LocalDate createdToDate = query.createdTo();
		model.addAttribute("allFilter", new StatusFilterLink(
			"전체", hrefWith(null, keyword, createdFromDate, createdToDate, currentSort), query.status() == null));
		model.addAttribute("statusFilters", statusOptions().stream()
			.map(option -> new StatusFilterLink(
				option.label(),
				hrefWith(option.value(), keyword, createdFromDate, createdToDate, currentSort),
				query.status() == option.value()))
			.toList());
		// 일괄 처리·저장된 뷰 변경 후 현재 필터·정렬 컨텍스트로 되돌아갈 returnTo.
		model.addAttribute("currentListHref",
			hrefWith(query.status(), keyword, createdFromDate, createdToDate, currentSort));
		model.addAttribute("statusSort", new SortHeaderLink(
			hrefWith(query.status(), keyword, createdFromDate, createdToDate, query.nextSortFor("status")),
			query.ariaSortFor("status")));
		model.addAttribute("createdSort", new SortHeaderLink(
			hrefWith(query.status(), keyword, createdFromDate, createdToDate, query.nextSortFor("created_at")),
			query.ariaSortFor("created_at")));

		// 기간 프리셋(오늘·최근 7일·최근 30일·전체 기간). 접수일 기준.
		LocalDate today = LocalDate.now(clock);
		model.addAttribute("datePresets", List.of(
			datePreset(query, "오늘", today, today, currentSort),
			datePreset(query, "최근 7일", today.minusDays(6), today, currentSort),
			datePreset(query, "최근 30일", today.minusDays(29), today, currentSort),
			new DatePresetLink("전체 기간",
				hrefWith(query.status(), keyword, null, null, currentSort),
				createdFromDate == null && createdToDate == null)
		));

		// 활성 필터 칩(개별 제거). 상태는 상단 필터 nav로 표현하므로 칩은 키워드·기간만 담는다.
		List<FilterChip> chips = new java.util.ArrayList<>();
		if (keyword != null) {
			chips.add(new FilterChip("검색: " + keyword,
				hrefWith(query.status(), null, createdFromDate, createdToDate, currentSort)));
		}
		if (createdFromDate != null || createdToDate != null) {
			chips.add(new FilterChip("기간: " + dateRangeLabel(createdFromDate, createdToDate),
				hrefWith(query.status(), keyword, null, null, currentSort)));
		}
		model.addAttribute("filterChips", chips);

		// 저장된 뷰(#1737): 계정별 목록 + 현재 검색을 저장할 질의 문자열. 목록 진입 시 적용/기본/삭제 링크.
		String loginId = authentication == null ? null : authentication.getName();
		model.addAttribute("currentQueryString", buildQueryString(query));
		model.addAttribute("savedViews", savedViewUseCase.listViews(loginId, REPORTS_PROGRAM_ID).stream()
			.map(view -> new SavedViewLink(
				view.name(), savedViewApplyHref(view.queryParams()), view.viewId(), view.isDefault()))
			.toList());
	}

	// 현재 필터·정렬을 인코딩된 질의 문자열로 만든다(page·size 제외). 저장된 뷰의 저장·적용에 쓴다.
	private static String buildQueryString(FacilityReportListQuery query) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/admin/reports/page");
		queryParams(query).forEach((key, value) -> appendParam(builder, key, value));
		String queryString = builder.build().encode().getQuery();
		return queryString == null ? "" : queryString;
	}

	private static String savedViewApplyHref(String queryParams) {
		if (queryParams == null || queryParams.isBlank()) {
			return "/admin/reports/page";
		}
		return "/admin/reports/page?" + queryParams;
	}

	private DatePresetLink datePreset(
		FacilityReportListQuery query,
		String label,
		LocalDate from,
		LocalDate to,
		String currentSort
	) {
		boolean active = from.equals(query.createdFrom()) && to.equals(query.createdTo());
		return new DatePresetLink(label, hrefWith(query.status(), query.keyword(), from, to, currentSort), active);
	}

	private static String dateRangeLabel(LocalDate from, LocalDate to) {
		if (from != null && to != null) {
			return from.equals(to) ? from.toString() : from + " ~ " + to;
		}
		return from != null ? from + " ~" : "~ " + to;
	}

	// 상태·검색·기간·정렬을 명시적으로 받아 링크 URL을 만든다. null 값은 생략한다.
	private static String hrefWith(
		FacilityReportStatus status,
		String keyword,
		LocalDate from,
		LocalDate to,
		String sort
	) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/admin/reports/page");
		appendParam(builder, "status", status);
		appendParam(builder, "keyword", keyword);
		appendParam(builder, "from", from);
		appendParam(builder, "to", to);
		appendParam(builder, "sort", sort);
		return builder.build().encode().toUriString();
	}

	private static void appendParam(UriComponentsBuilder builder, String name, Object value) {
		if (value != null && !value.toString().isBlank()) {
			builder.queryParam(name, value);
		}
	}

	record StatusFilterLink(String label, String href, boolean current) {
	}

	record SortHeaderLink(String href, String ariaSort) {
	}

	record DatePresetLink(String label, String href, boolean active) {
	}

	record FilterChip(String label, String removeHref) {
	}

	record SavedViewLink(String name, String applyHref, String viewId, boolean isDefault) {
	}

	// 급증 경고·처리 시간 카드는 fragment 밖 풀페이지 전용이라 부분 응답에서는 조회하지 않는다.
	private void addReportSummaryAttributes(Model model) {
		LocalDateTime surgeCutoff = LocalDateTime.now(clock).minusHours(REPORT_SURGE_LOOKBACK_HOURS);
		model.addAttribute("reportSurgeAlert", ReportSurgeAlertView.from(
			facilityReportUseCase.countReportsCreatedSince(surgeCutoff)
		));
		model.addAttribute("processingTime", ReportProcessingTimeView.from(
			facilityReportUseCase.summarizeReportProcessingTime()
		));
	}

	// 페이지네이션·필터 링크가 현재 검색 상태를 유지하도록 활성 파라미터를 함께 전달한다(널·기본값은 생략).
	private static Map<String, Object> queryParams(FacilityReportListQuery query) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("status", query.status());
		params.put("keyword", query.keyword());
		params.put("from", query.createdFrom());
		params.put("to", query.createdTo());
		if (query.sortField() != FacilityReportListQuery.SortField.CREATED_AT
			|| query.sortDirection() != FacilityReportListQuery.SortDirection.DESC) {
			params.put("sort", query.sortToken());
		}
		return params;
	}

	private static String redirectToReportList(FacilityReportListQuery query, EgovPaginationView pageView) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/admin/reports/page");
		queryParams(query).forEach((key, value) -> {
			if (value != null && !value.toString().isBlank()) {
				builder.queryParam(key, value);
			}
		});
		builder.queryParam("page", pageView.page());
		builder.queryParam("size", pageView.size());
		return "redirect:" + builder.build().encode().toUriString();
	}

	@GetMapping("/admin/reports/{reportId}/page")
	String reportDetailPage(
		@PathVariable String reportId,
		Model model,
		Authentication authentication,
		HttpServletRequest request
	) {
		populateReportDetailModel(reportId, model, null);
		auditReportDetailRead(authentication, request, reportId);
		return "admin/reports/detail";
	}

	// 드로어(사이드 패널) 파일럿(#1737): 같은 URL을 htmx로 열면 상세 본문 fragment만 반환하고
	// HX-Trigger로 admin-drawer-open 이벤트를 쏴 패널을 연다. no-JS에서는 상세 링크가 상세 페이지로 이동한다.
	@HxRequest
	@GetMapping("/admin/reports/{reportId}/page")
	@HxTrigger("admin-drawer-open")
	String reportDetailDrawer(
		@PathVariable String reportId,
		Model model,
		Authentication authentication,
		HttpServletRequest request
	) {
		populateReportDetailModel(reportId, model, null);
		auditReportDetailRead(authentication, request, reportId);
		return "admin/reports/detail :: detailBody";
	}

	private void populateReportDetailModel(String reportId, Model model, ReviewReportForm submittedForm) {
		model.addAttribute("report", FacilityReportDetailPageView.from(facilityReportUseCase.getReport(reportId), messages));
		model.addAttribute(
			"reviewAudits",
			facilityReportUseCase.listReviewAudits(reportId)
				.stream()
				.map(audit -> FacilityReportReviewAuditPageRow.from(audit, messages))
				.toList()
		);
		model.addAttribute("reviewActions", reviewActions());
		model.addAttribute("reviewForm", submittedForm == null ? new ReviewReportForm(null, "") : submittedForm);
	}

	@GetMapping("/admin/reports/{reportId}/photo/thumbnail")
	@PreAuthorize("hasAuthority('admin.report.photo.read')")
	ResponseEntity<byte[]> reportPhotoThumbnail(
		@PathVariable String reportId,
		Authentication authentication,
		HttpServletRequest request
	) {
		return reportPhoto(reportId, FacilityReport::photoThumbnailObjectKey, "VIEW_REPORT_PHOTO_THUMBNAIL", "업무 맥락: 신고 사진 미리보기 조회", authentication, request);
	}

	@GetMapping("/admin/reports/{reportId}/photo/original")
	@PreAuthorize("hasAuthority('admin.report.photo.read')")
	ResponseEntity<byte[]> reportPhotoOriginal(
		@PathVariable String reportId,
		Authentication authentication,
		HttpServletRequest request
	) {
		return reportPhoto(reportId, FacilityReport::photoObjectKey, "VIEW_REPORT_PHOTO_ORIGINAL", "업무 맥락: 신고 원본 사진 조회", authentication, request);
	}

	private ResponseEntity<byte[]> reportPhoto(
		String reportId,
		Function<FacilityReport, String> objectKey,
		String action,
		String reason,
		Authentication authentication,
		HttpServletRequest request
	) {
		String key = objectKey.apply(facilityReportUseCase.getReport(reportId));
		if (!hasText(key)) {
			return ResponseEntity.notFound().build();
		}
		return loadFacilityReportPhotoPort.loadFacilityReportPhoto(key)
			.map(photo -> {
				auditWriter.privacyRead(
					authentication,
					request,
					"FACILITY_REPORT_PHOTO",
					reportId,
					action,
					reason
				);
				return ResponseEntity.ok()
					.cacheControl(CacheControl.noStore().cachePrivate())
					.contentType(MediaType.parseMediaType(photo.contentType()))
					.body(photo.bytes());
			})
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping("/admin/reports/{reportId}/page/review")
	@PreAuthorize("hasAuthority('admin.report.review')")
	String reviewReportFromPage(
		@PathVariable String reportId,
		@Valid @ModelAttribute("reviewForm") ReviewReportForm form,
		BindingResult bindingResult,
		Principal principal,
		RedirectAttributes redirectAttributes,
		Model model,
		HttpServletResponse response,
		Authentication authentication,
		HttpServletRequest request
	) {
		if (bindingResult.hasErrors()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			populateReportDetailModel(reportId, model, form);
			auditReportDetailRead(authentication, request, reportId);
			AdminFormErrorView.expose(model, bindingResult);
			return "admin/reports/detail";
		}
		try {
			facilityReportUseCase.reviewReport(
				new ReviewFacilityReportCommand(reportId, form.decision(), principal.getName(), form.duplicateOfReportId())
			);
		} catch (MasterDataWriteNotAllowedException exception) {
			redirectAttributes.addFlashAttribute("masterDataError", exception.getMessage());
		}
		return "redirect:/admin/reports/%s/page".formatted(reportId);
	}

	// 일괄 검수(#1737): 선택한 신고들을 한 번에 승인/반려한다. no-JS 폼으로 동작하며 개별 실패는
	// 집계해 안내한다(이미 처리됨·읽기 전용 마스터·낙관적 락 충돌 등은 실패로 센다).
	@PostMapping("/admin/reports/bulk-review")
	@PreAuthorize("hasAuthority('admin.report.review')")
	String bulkReviewReports(
		@RequestParam(name = "reportIds", required = false) List<String> reportIds,
		@RequestParam FacilityReportReviewDecision decision,
		@RequestParam(required = false) String returnTo,
		Principal principal,
		RedirectAttributes redirectAttributes
	) {
		List<String> ids = reportIds == null ? List.of() : reportIds;
		int processed = 0;
		int failed = 0;
		for (String reportId : ids) {
			try {
				facilityReportUseCase.reviewReport(
					new ReviewFacilityReportCommand(reportId, decision, principal.getName(), null));
				processed++;
			} catch (RuntimeException exception) {
				failed++;
			}
		}
		redirectAttributes.addFlashAttribute("flashMessage", bulkReviewMessage(ids.size(), processed, failed));
		redirectAttributes.addFlashAttribute("flashTone", failed == 0 ? "good" : "warning");
		// 처리 후 현재 필터·정렬 컨텍스트로 되돌아간다(open redirect 방지: 목록 경로만 허용).
		return "redirect:" + safeReportListReturnTo(returnTo);
	}

	private static String safeReportListReturnTo(String returnTo) {
		if (returnTo != null
			&& returnTo.startsWith("/admin/reports/page")
			&& !returnTo.contains("://")
			&& !returnTo.contains("\n")
			&& !returnTo.contains("\r")) {
			return returnTo;
		}
		return "/admin/reports/page";
	}

	private static String bulkReviewMessage(int total, int processed, int failed) {
		if (total == 0) {
			return "선택한 신고가 없습니다.";
		}
		if (failed == 0) {
			return "선택한 신고 %d건을 처리했습니다.".formatted(processed);
		}
		return "선택한 %d건 중 %d건 처리, %d건은 처리하지 못했습니다.".formatted(total, processed, failed);
	}

	private void auditReportDetailRead(Authentication authentication, HttpServletRequest request, String reportId) {
		auditWriter.privacyRead(
			authentication,
			request,
			"FACILITY_REPORT",
			reportId,
			"VIEW_REPORT_DETAIL",
			"업무 맥락: 신고 상세 조회"
		);
	}

	private List<ReviewAction> reviewActions() {
		return List.of(
			new ReviewAction(
				FacilityReportReviewDecision.ACCEPT,
				messages.enumLabel("admin.report.review-decision", FacilityReportReviewDecision.ACCEPT)
			),
			new ReviewAction(
				FacilityReportReviewDecision.REJECT,
				messages.enumLabel("admin.report.review-decision", FacilityReportReviewDecision.REJECT)
			),
			new ReviewAction(
				FacilityReportReviewDecision.MARK_DUPLICATE,
				messages.enumLabel("admin.report.review-decision", FacilityReportReviewDecision.MARK_DUPLICATE)
			)
		);
	}

	private List<StatusOption> statusOptions() {
		return Arrays.stream(FacilityReportStatus.values())
			.map(status -> new StatusOption(status, messages.enumLabel("admin.report.status", status)))
			.toList();
	}

	private static String coordinateLabel(BigDecimal latitude, BigDecimal longitude) {
		if (latitude == null || longitude == null) {
			return "위치 없음";
		}
		return "%s, %s".formatted(latitude.toPlainString(), longitude.toPlainString());
	}

	private static boolean hasCompletePhoto(FacilityReport report) {
		return report.hasPhoto();
	}

	private static boolean hasCompletePhoto(FacilityReportSummary report) {
		return report.hasPhoto();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	record FacilityReportListPageRow(
		String id,
		String stationId,
		String facilityId,
		String stationLabel,
		String facilityLabel,
		String reportTypeLabel,
		String description,
		String statusLabel,
		LocalDateTime createdAt,
		boolean hasPhoto,
		String coordinateLabel
	) {

		static FacilityReportListPageRow from(
			FacilityReportSummary report,
			WebMessageResolver messages,
			Map<String, String> stationLabels,
			Map<String, String> facilityLabels
		) {
			return new FacilityReportListPageRow(
				report.id(),
				report.stationId(),
				report.facilityId(),
				stationLabels.getOrDefault(report.stationId(), report.stationId()),
				facilityLabels.getOrDefault(report.facilityId(), report.facilityId()),
				messages.enumLabel("admin.report.type", report.reportType()),
				report.description(),
				messages.enumLabel("admin.report.status", report.status()),
				report.createdAt(),
				FacilityReportAdminPageController.hasCompletePhoto(report),
				FacilityReportAdminPageController.coordinateLabel(report.latitude(), report.longitude())
			);
		}
	}

	record FacilityReportDetailPageView(
		String id,
		String userId,
		String stationId,
		String facilityId,
		String reportTypeLabel,
		String description,
		String statusLabel,
		LocalDateTime createdAt,
		LocalDateTime reviewedAt,
		String reviewedBy,
		String photoFileName,
		String photoContentType,
		String photoObjectKey,
		String photoThumbnailObjectKey,
		String photoSha256,
		Long photoSizeBytes,
		String duplicateOfReportId,
		String coordinateLabel,
		String correctionOverrideHref
	) {

		static FacilityReportDetailPageView from(FacilityReport report, WebMessageResolver messages) {
			return new FacilityReportDetailPageView(
				report.id(),
				report.userId(),
				report.stationId(),
				report.facilityId(),
				messages.enumLabel("admin.report.type", report.reportType()),
				report.description(),
				messages.enumLabel("admin.report.status", report.status()),
				report.createdAt(),
				report.reviewedAt(),
				report.reviewedBy(),
				report.photoFileName(),
				report.photoContentType(),
				report.photoObjectKey(),
				report.photoThumbnailObjectKey(),
				report.photoSha256(),
				report.photoSizeBytes(),
				report.duplicateOfReportId(),
				FacilityReportAdminPageController.coordinateLabel(report.latitude(), report.longitude()),
				FacilityReportAdminPageController.correctionOverrideHref(report)
			);
		}

		public boolean hasPhoto() {
			return FacilityReportAdminPageController.hasText(photoFileName)
				&& FacilityReportAdminPageController.hasText(photoContentType)
				&& FacilityReportAdminPageController.hasText(photoObjectKey);
		}
	}

	private static String correctionOverrideHref(FacilityReport report) {
		if (!isFacilityStatusEvidence(report)) {
			return null;
		}
		return UriComponentsBuilder.fromPath("/admin/datapack/manual-overrides/page")
			.queryParam("id", "override-" + report.id())
			.queryParam("entityType", "FACILITY")
			.queryParam("entityId", report.facilityId())
			.queryParam("fieldName", "operational_status")
			.queryParam("reasonCode", "FIELD_REPORT")
			.queryParam("reason", "신고 검수 후 임시 override")
			.queryParam("evidenceUri", "/admin/reports/%s/page".formatted(report.id()))
			.queryParam("idempotencyKey", "override-" + report.id())
			.build()
			.encode()
			.toUriString();
	}

	private static boolean isFacilityStatusEvidence(FacilityReport report) {
		return switch (report.reportType()) {
			case BROKEN, ELEVATOR_UNAVAILABLE, UNDER_CONSTRUCTION, CLOSED, RECOVERED -> true;
			case ROUTE_BLOCKED, STAIRS_PRESENT, ETA_INACCURATE, TRANSFER_IMPOSSIBLE, LOCATION_WRONG, INFORMATION_WRONG -> false;
		};
	}

	record StatusOption(FacilityReportStatus value, String label) {
	}

	record ReportSurgeAlertView(
		String title,
		String label,
		String description,
		String alertClass
	) {

		static ReportSurgeAlertView from(long recentReportCount) {
			if (recentReportCount >= REPORT_SURGE_ALERT_THRESHOLD) {
				return new ReportSurgeAlertView(
					"신고 급증",
					"점검 필요",
					"최근 24시간 신고 %d건입니다. 신고가 평소보다 많습니다.".formatted(recentReportCount),
					"warning"
				);
			}
			return new ReportSurgeAlertView(
				"신고 급증",
				"정상",
				"최근 24시간 신고 %d건입니다. 접수량은 정상 범위입니다.".formatted(recentReportCount),
				"normal"
			);
		}
	}

	record ReportProcessingTimeView(
		String title,
		String label,
		String description,
		String metricClass
	) {

		static ReportProcessingTimeView from(ReportProcessingTimeSummary summary) {
			if (summary.reviewedReportCount() == 0) {
				return new ReportProcessingTimeView(
					"신고 처리 시간",
					"처리 완료 신고 없음",
					"처리 완료 후 평균 시간을 표시합니다.",
					"empty"
				);
			}

			return new ReportProcessingTimeView(
				"신고 처리 시간",
				"평균 " + durationLabel(summary.averageProcessingMinutes()),
				"처리 완료 신고 %d건 기준입니다.".formatted(summary.reviewedReportCount()),
				"ok"
			);
		}

		private static String durationLabel(long minutes) {
			if (minutes < 60) {
				return minutes + "분";
			}
			long hours = minutes / 60;
			long remainingMinutes = minutes % 60;
			if (remainingMinutes == 0) {
				return hours + "시간";
			}
			return "%d시간 %d분".formatted(hours, remainingMinutes);
		}
	}

	record ReviewAction(FacilityReportReviewDecision value, String label) {
	}

	record ReviewReportForm(
		@NotNull(message = "{validation.report.review-decision.required}")
		FacilityReportReviewDecision decision,
		String duplicateOfReportId
	) {
	}

	record FacilityReportReviewAuditPageRow(
		String reviewerId,
		String decisionLabel,
		String previousStatusLabel,
		String nextStatusLabel,
		LocalDateTime createdAt
	) {

		static FacilityReportReviewAuditPageRow from(FacilityReportReviewAudit audit, WebMessageResolver messages) {
			return new FacilityReportReviewAuditPageRow(
				audit.reviewerId(),
				messages.enumLabel("admin.report.review-decision", audit.decision()),
				messages.enumLabel("admin.report.status", audit.previousStatus()),
				messages.enumLabel("admin.report.status", audit.nextStatus()),
				audit.createdAt()
			);
		}
	}
}
