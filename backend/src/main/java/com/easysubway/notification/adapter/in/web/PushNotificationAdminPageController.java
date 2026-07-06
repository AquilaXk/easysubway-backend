package com.easysubway.notification.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.admin.code.application.service.AdminCommonCodeService;
import com.easysubway.admin.code.domain.AdminCommonCode;
import com.easysubway.admin.code.domain.AdminCommonCodeGroups;
import com.easysubway.admin.metric.adapter.in.web.AnalyticsComparisonCard;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricChart;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricSeries;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import com.easysubway.admin.metric.domain.AdminMetricSparkline;
import com.easysubway.common.domain.PageResult;
import com.easysubway.common.web.pagination.EgovPaginationView;
import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import com.easysubway.notification.application.port.in.PushNotificationHistoryQuery;
import com.easysubway.notification.application.port.in.PushNotificationHistoryUseCase;
import com.easysubway.notification.application.port.in.PushNotificationResendUseCase;
import com.easysubway.notification.application.port.in.ResendPushNotificationsCommand;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import com.easysubway.notification.domain.PushNotificationFailureReasonCount;
import com.easysubway.notification.domain.PushNotificationResendResult;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
class PushNotificationAdminPageController {

	// 발송 시도는 도달(증가)이, 발송 실패는 감소가 좋은 신호다(증감 카드 tone 판정).
	private static final Set<String> HIGHER_IS_BETTER = Set.of(AdminMetricKeys.PUSH_ATTEMPTED);
	private static final List<String> TREND_KEYS =
		List.of(AdminMetricKeys.PUSH_ATTEMPTED, AdminMetricKeys.PUSH_FAILED);
	// URL에 "/page"가 있어야 AdminHtmlRequest.matches가 commandTokens를 노출한다(#1742 gotcha와 동일).
	private static final String HISTORY_PATH = "/admin/notifications/push/page/history";
	private static final String PUSH_PAGE_PATH = "/admin/notifications/push/page";
	// 공통코드가 없거나 숫자가 아닐 때의 안전한 기본 상한(대량 오발송 방지).
	private static final int DEFAULT_RESEND_LIMIT = 50;

	private final PushNotificationDashboardUseCase pushNotificationDashboardUseCase;
	private final PushNotificationHistoryUseCase pushNotificationHistoryUseCase;
	private final PushNotificationResendUseCase pushNotificationResendUseCase;
	private final AdminMetricQueryService metricQueryService;
	private final AdminCommonCodeService commonCodeService;
	private final AdminAuditWriter auditWriter;
	private final ObjectMapper objectMapper;

	PushNotificationAdminPageController(
		PushNotificationDashboardUseCase pushNotificationDashboardUseCase,
		PushNotificationHistoryUseCase pushNotificationHistoryUseCase,
		PushNotificationResendUseCase pushNotificationResendUseCase,
		AdminMetricQueryService metricQueryService,
		AdminCommonCodeService commonCodeService,
		AdminAuditWriter auditWriter,
		ObjectMapper objectMapper
	) {
		this.pushNotificationDashboardUseCase = pushNotificationDashboardUseCase;
		this.pushNotificationHistoryUseCase = pushNotificationHistoryUseCase;
		this.pushNotificationResendUseCase = pushNotificationResendUseCase;
		this.metricQueryService = metricQueryService;
		this.commonCodeService = commonCodeService;
		this.auditWriter = auditWriter;
		this.objectMapper = objectMapper;
	}

	@GetMapping("/admin/notifications/push/page")
	String pushNotificationDashboardPage(
		@RequestParam(name = "days", defaultValue = "7") int days,
		@RequestParam(name = "status", required = false) PushNotificationStatus status,
		@RequestParam(name = "type", required = false) PushNotificationType type,
		@RequestParam(name = "keyword", required = false) String keyword,
		@RequestParam(name = "reason", required = false) String reason,
		@RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(name = "page", required = false) Integer page,
		Authentication authentication,
		HttpServletRequest request,
		Model model
	) {
		PushNotificationDashboardSummary summary = pushNotificationDashboardUseCase.summarizePushNotifications();
		model.addAttribute("summary", PushNotificationDashboardView.from(summary));
		populateTrends(days, model);
		populateSummarySparklines(model);
		populateHistory(historyQuery(status, type, keyword, reason, from, to, page), authentication, request, model);
		return "admin/notifications/push";
	}

	// 요약 카드 7일 스파크라인(#1746): 발송 시도·실패의 최근 7일 추이를 CSP-safe 서버 SVG polyline으로 그린다.
	// 기간 버튼과 무관하게 항상 7일이며, 실패 카드에는 실패 목록 필터 딥링크를 함께 노출한다.
	private void populateSummarySparklines(Model model) {
		AdminMetricChart weekChart = metricQueryService.chart(TREND_KEYS, 7);
		model.addAttribute("attemptedSparkline", sparklinePoints(weekChart, AdminMetricKeys.PUSH_ATTEMPTED));
		model.addAttribute("failedSparkline", sparklinePoints(weekChart, AdminMetricKeys.PUSH_FAILED));
		// 실패 경고·실패 카드에서 실패 이력으로 바로 가는 딥링크.
		model.addAttribute("failedHistoryHref", PUSH_PAGE_PATH + "?status=FAILED");
	}

	private static String sparklinePoints(AdminMetricChart chart, String key) {
		return chart.series().stream()
			.filter(series -> series.key().equals(key))
			.findFirst()
			.map(AdminMetricSeries::values)
			.map(values -> AdminMetricSparkline.points(values, 100, 24))
			.orElse("");
	}

	// no-JS 발송 이력 필터: 폼 제출이 이 경로로 GET하면 이력이 채워진 풀페이지를 돌려준다.
	@GetMapping(HISTORY_PATH)
	String pushNotificationHistoryPage(
		@RequestParam(name = "status", required = false) PushNotificationStatus status,
		@RequestParam(name = "type", required = false) PushNotificationType type,
		@RequestParam(name = "keyword", required = false) String keyword,
		@RequestParam(name = "reason", required = false) String reason,
		@RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(name = "page", required = false) Integer page,
		Authentication authentication,
		HttpServletRequest request,
		Model model
	) {
		return pushNotificationDashboardPage(
			7, status, type, keyword, reason, from, to, page, authentication, request, model);
	}

	// 발송 이력 부분 갱신(#1746): 필터·페이지 링크가 이 fragment를 htmx로 다시 불러 표·페이지네이션만 갈아끼운다.
	// htmx 히스토리 복원 요청은 셸을 포함한 풀페이지를 돌려줘 화면이 깨지지 않게 한다.
	@HxRequest
	@GetMapping(HISTORY_PATH)
	String pushNotificationHistoryFragment(
		@RequestParam(name = "status", required = false) PushNotificationStatus status,
		@RequestParam(name = "type", required = false) PushNotificationType type,
		@RequestParam(name = "keyword", required = false) String keyword,
		@RequestParam(name = "reason", required = false) String reason,
		@RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(name = "page", required = false) Integer page,
		@RequestHeader(value = "HX-History-Restore-Request", required = false) boolean historyRestore,
		Authentication authentication,
		HttpServletRequest request,
		Model model
	) {
		if (historyRestore) {
			return pushNotificationHistoryPage(
				status, type, keyword, reason, from, to, page, authentication, request, model);
		}
		populateHistory(historyQuery(status, type, keyword, reason, from, to, page), authentication, request, model);
		return "admin/notifications/push :: historyResults";
	}

	// 실패 푸시 재발송(#1746): 선택 실패 건을 재발송한다. no-JS 폼 기준선(command token + CSRF는 인터셉터가 검증).
	// 멱등(성공 건 제외)·1회 상한(대량 오발송 방지)은 유스케이스가 보장하고, 결과는 flash 토스트로 안내한다.
	@PostMapping("/admin/notifications/push/resend")
	String resendPushNotifications(
		@RequestParam(name = "notificationIds", required = false) List<String> notificationIds,
		@RequestParam(name = "returnTo", required = false) String returnTo,
		Authentication authentication,
		HttpServletRequest request,
		RedirectAttributes redirectAttributes
	) {
		int limit = resendLimit();
		List<String> ids = notificationIds == null ? List.of() : notificationIds;
		PushNotificationResendResult result = pushNotificationResendUseCase.resend(
			new ResendPushNotificationsCommand(ids, limit));

		auditWriter.pushResend(
			authentication,
			request,
			"selection",
			"RESEND_PUSH",
			result.blocked() ? AdminAuditOutcome.FAILURE : AdminAuditOutcome.SUCCESS,
			"업무 맥락: 실패 푸시 재발송 요청 %d건(상한 %d)".formatted(result.requestedCount(), limit)
		);

		redirectAttributes.addFlashAttribute("flashMessage", resendMessage(result));
		redirectAttributes.addFlashAttribute("flashTone", resendTone(result));
		return "redirect:" + safePushReturnTo(returnTo);
	}

	private static String resendMessage(PushNotificationResendResult result) {
		if (result.blocked()) {
			return "선택 %d건이 1회 재발송 상한(%d건)을 초과해 재발송하지 않았습니다. 나눠서 다시 시도해 주세요."
				.formatted(result.requestedCount(), result.maxPerResend());
		}
		if (result.requestedCount() == 0) {
			return "재발송할 항목을 선택해 주세요.";
		}
		if (result.resentCount() == 0) {
			return "선택 %d건은 이미 처리되었거나 실패 상태가 아니라 재발송하지 않았습니다.".formatted(result.requestedCount());
		}
		if (result.skippedCount() == 0) {
			return "실패 %d건을 재발송했습니다.".formatted(result.resentCount());
		}
		return "실패 %d건을 재발송하고, %d건은 이미 처리되어 제외했습니다."
			.formatted(result.resentCount(), result.skippedCount());
	}

	private static String resendTone(PushNotificationResendResult result) {
		if (result.blocked() || result.requestedCount() == 0 || result.resentCount() == 0) {
			return "warning";
		}
		return "good";
	}

	// open-redirect 방지: 푸시 화면 경로(/page·/page/history)만 허용한다. 넓은 접두사(.../push)는
	// /pushEVIL 같은 우회를 허용하므로 /page 전체 접두사로 좁힌다.
	private static String safePushReturnTo(String returnTo) {
		if (returnTo != null
			&& returnTo.startsWith(PUSH_PAGE_PATH)
			&& !returnTo.contains("://")
			&& !returnTo.contains("\n")
			&& !returnTo.contains("\r")) {
			return returnTo;
		}
		return PUSH_PAGE_PATH;
	}

	// 1회 재발송 상한을 공통코드(PUSH_RESEND_LIMIT)에서 읽는다. 값은 code 문자열의 정수이며, 없거나
	// 숫자가 아니면 안전한 기본값으로 폴백한다. 활성 code가 여러 개면 가장 작은 값(가장 보수적)을 쓴다.
	private int resendLimit() {
		return commonCodeService.enabledCodes(AdminCommonCodeGroups.PUSH_RESEND_LIMIT).stream()
			.map(AdminCommonCode::code)
			.map(PushNotificationAdminPageController::parsePositiveInt)
			.filter(value -> value != null && value > 0)
			.min(Integer::compareTo)
			.orElse(DEFAULT_RESEND_LIMIT);
	}

	private static Integer parsePositiveInt(String value) {
		try {
			return Integer.valueOf(value.trim());
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static PushNotificationHistoryQuery historyQuery(
		PushNotificationStatus status,
		PushNotificationType type,
		String keyword,
		String reason,
		LocalDate from,
		LocalDate to,
		Integer page
	) {
		return PushNotificationHistoryQuery.of(status, type, keyword, reason, from, to, page, null);
	}

	// 발송 이력 표준 테이블: 필터·페이지네이션이 적용된 목록을 채운다. 수신자 식별자는 마스킹되며
	// 열람은 감사에 남긴다(개인정보 최소 노출 원칙). 목록·건수·분해가 같은 질의를 공유해 정합을 보장한다.
	private void populateHistory(
		PushNotificationHistoryQuery query,
		Authentication authentication,
		HttpServletRequest request,
		Model model
	) {
		long total = pushNotificationHistoryUseCase.countPushNotifications(query);
		EgovPaginationView pageView = EgovPaginationView.from(query.page(), query.size(), total);
		PushNotificationHistoryQuery pageQuery = query.withPage(pageView.page());
		PageResult<PushNotification> historyPage = pushNotificationHistoryUseCase.searchPushNotifications(pageQuery);
		List<PushNotificationHistoryRow> rows = historyPage.items().stream()
			.map(PushNotificationHistoryRow::from)
			.toList();

		model.addAttribute("historyRows", rows);
		model.addAttribute("historyPage", pageView);
		model.addAttribute("historyTotal", total);
		model.addAttribute("historyPaginationLinks", pageView.links(HISTORY_PATH, historyParams(pageQuery)));
		model.addAttribute("historySelectedStatus", pageQuery.status());
		model.addAttribute("historySelectedType", pageQuery.type());
		model.addAttribute("historyKeyword", pageQuery.keyword());
		model.addAttribute("historyFrom", pageQuery.createdFrom());
		model.addAttribute("historyTo", pageQuery.createdTo());
		model.addAttribute("historyStatusOptions", statusOptions(pageQuery.status()));
		model.addAttribute("historyTypeOptions", typeOptions(pageQuery.type()));

		// 실패 사유별 분해(막대) + 드릴다운. 목록과 같은 필터 컨텍스트를 공유해 분해 수치 = 사유 필터 목록 건수 정합.
		List<PushNotificationFailureReasonCount> breakdown =
			pushNotificationHistoryUseCase.summarizeFailureReasons(pageQuery);
		long maxReasonCount = breakdown.stream()
			.mapToLong(PushNotificationFailureReasonCount::count)
			.max()
			.orElse(0L);
		List<FailureBreakdownBar> bars = breakdown.stream()
			.map(item -> new FailureBreakdownBar(
				item.reason(),
				item.count(),
				maxReasonCount == 0 ? 0 : Math.round(item.count() * 100.0 / maxReasonCount),
				historyReasonHref(pageQuery, item.reason()),
				item.reason().equals(pageQuery.failureReason())))
			.toList();
		model.addAttribute("failureBreakdown", bars);
		model.addAttribute("hasReasonFilter", pageQuery.hasFailureReason());
		model.addAttribute("selectedReason", pageQuery.failureReason());
		model.addAttribute("clearReasonHref", historyReasonHref(pageQuery, null));

		// 재발송 안전장치(#1746): 실패 행 선택 → 재발송 폼의 확인 단계에 노출할 1회 상한·되돌아갈 목록 URL.
		model.addAttribute("resendLimit", resendLimit());
		model.addAttribute("currentListHref", historyReasonHref(pageQuery, pageQuery.failureReason()));

		// 마스킹된 수신자 식별자를 노출하는 조회라 열람 자체를 감사에 남긴다(원문·free-text 없음).
		auditWriter.privacyRead(
			authentication,
			request,
			"PUSH_NOTIFICATION_HISTORY",
			"list",
			"VIEW_PUSH_HISTORY",
			"업무 맥락: 푸시 발송 이력 조회(수신자 식별자 마스킹)"
		);
	}

	// 페이지네이션·필터 링크가 현재 필터를 유지하도록 활성 파라미터만 전달한다(널·빈 값은 생략).
	private static Map<String, Object> historyParams(PushNotificationHistoryQuery query) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("status", query.status());
		params.put("type", query.type());
		params.put("keyword", query.keyword());
		params.put("reason", query.failureReason());
		params.put("from", query.createdFrom());
		params.put("to", query.createdTo());
		return params;
	}

	// 사유 드릴다운 링크: 현재 필터(상태·유형·검색·기간)를 유지하고 reason만 설정/해제한다(페이지는 처음으로).
	private static String historyReasonHref(PushNotificationHistoryQuery query, String reason) {
		Map<String, Object> params = new LinkedHashMap<>(historyParams(query));
		params.put("reason", reason);
		UriComponentsBuilder builder = UriComponentsBuilder.fromPath(HISTORY_PATH);
		params.forEach((name, value) -> {
			if (value != null && !value.toString().isBlank()) {
				builder.queryParam(name, value);
			}
		});
		return builder.build().encode().toUriString();
	}

	record FailureBreakdownBar(String reason, long count, long percent, String href, boolean active) {
	}

	private static List<FilterOption> statusOptions(PushNotificationStatus selected) {
		List<FilterOption> options = new java.util.ArrayList<>();
		options.add(new FilterOption("", "상태 전체", selected == null));
		for (PushNotificationStatus status : PushNotificationStatus.values()) {
			options.add(new FilterOption(
				status.name(), PushNotificationHistoryRow.statusLabel(status), status == selected));
		}
		return options;
	}

	private static List<FilterOption> typeOptions(PushNotificationType selected) {
		List<FilterOption> options = new java.util.ArrayList<>();
		options.add(new FilterOption("", "유형 전체", selected == null));
		for (PushNotificationType type : PushNotificationType.values()) {
			options.add(new FilterOption(
				type.name(), PushNotificationHistoryRow.typeLabel(type), type == selected));
		}
		return options;
	}

	record FilterOption(String value, String label, boolean selected) {
	}

	// 실패 분석 추이·증감 부분 갱신(#1746): 기간 버튼이 이 fragment를 htmx로 다시 불러 차트·증감·대체표를 갈아끼운다.
	@HxRequest
	@GetMapping("/admin/notifications/push/trends")
	String pushNotificationTrends(
		@RequestParam(name = "days", defaultValue = "7") int days,
		Model model
	) {
		populateTrends(days, model);
		return "admin/notifications/push :: trends";
	}

	private void populateTrends(int days, Model model) {
		AdminMetricChart chart = metricQueryService.chart(TREND_KEYS, days);
		model.addAttribute("trendChart", chart);
		model.addAttribute("trendJson", toJson(chart));
		model.addAttribute("trendDays", chart.days());
		model.addAttribute("exportKeys", TREND_KEYS);
		model.addAttribute("comparisons", metricQueryService.compare(TREND_KEYS, days)
			.stream()
			.map(comparison -> AnalyticsComparisonCard.from(comparison, HIGHER_IS_BETTER.contains(comparison.key())))
			.toList());
	}

	// Chart.js가 읽을 데이터 섬(JSON). 직렬화 실패 시 빈 차트로 안전 폴백(details 표가 대체).
	private String toJson(AdminMetricChart chart) {
		try {
			return objectMapper.writeValueAsString(chart);
		} catch (JsonProcessingException exception) {
			return "{\"labels\":[],\"series\":[]}";
		}
	}
}
