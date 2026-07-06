package com.easysubway.admin.audit.adapter.in.web;

import com.easysubway.admin.audit.application.AdminAuditActorContext;
import com.easysubway.admin.audit.application.AdminAuditQuery;
import com.easysubway.admin.audit.application.port.out.AdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.common.web.pagination.EgovPaginationView;
import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxRequest;
import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxTrigger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관리자 감사·개인정보 조회 로그 표준 테이블(#1747). 유형·actor·결과·기간·target 검색·"사유 없는 조회"
 * 필터를 서버 질의로 적용하고, htmx로 결과 fragment만 부분 갱신한다(no-JS는 폼 제출로 풀페이지 동작).
 *
 * <p>두 프로그램(관리자 감사·개인정보 조회 로그)이 같은 템플릿을 공유하되, 개인정보 화면은
 * {@code PRIVACY_READ}로 유형을 강제해 권한 분리를 URL이 아니라 질의로도 보장한다.
 */
@Controller
class AdminAuditPageController {

	private static final String AUDITS_PATH = "/admin/audits/page";
	private static final String PRIVACY_PATH = "/admin/audits/privacy/page";
	private static final String AUDITS_BASE = "/admin/audits";
	private static final String PRIVACY_BASE = "/admin/audits/privacy";

	private final AdminAuditEventRepository auditEventRepository;

	AdminAuditPageController(AdminAuditEventRepository auditEventRepository) {
		this.auditEventRepository = auditEventRepository;
	}

	@GetMapping(AUDITS_PATH)
	@PreAuthorize("hasAuthority('admin.audit.read')")
	String auditPage(AuditFilterParams params, Model model) {
		populateAuditModel(model, auditContext(), params);
		return "admin/audits/list";
	}

	// 결과 부분 갱신(#1747): 필터·페이지 링크가 이 fragment를 htmx로 다시 불러 표·페이지네이션만 갈아끼운다.
	// htmx 히스토리 복원 요청은 셸을 포함한 풀페이지를 돌려줘 화면이 깨지지 않게 한다.
	@HxRequest
	@GetMapping(AUDITS_PATH)
	@PreAuthorize("hasAuthority('admin.audit.read')")
	String auditFragment(
		AuditFilterParams params,
		@RequestHeader(value = "HX-History-Restore-Request", required = false) boolean historyRestore,
		Model model
	) {
		populateAuditModel(model, auditContext(), params);
		return historyRestore ? "admin/audits/list" : "admin/audits/list :: auditResults";
	}

	@GetMapping(PRIVACY_PATH)
	@PreAuthorize("hasAuthority('admin.privacy-log.read')")
	String privacyAuditPage(AuditFilterParams params, Model model) {
		populateAuditModel(model, privacyContext(), params);
		return "admin/audits/list";
	}

	@HxRequest
	@GetMapping(PRIVACY_PATH)
	@PreAuthorize("hasAuthority('admin.privacy-log.read')")
	String privacyAuditFragment(
		AuditFilterParams params,
		@RequestHeader(value = "HX-History-Restore-Request", required = false) boolean historyRestore,
		Model model
	) {
		populateAuditModel(model, privacyContext(), params);
		return historyRestore ? "admin/audits/list" : "admin/audits/list :: auditResults";
	}

	// 상세 드로어 전후 타임라인의 반경(같은 actor 직전·직후 각 5건).
	private static final int TIMELINE_RADIUS = 5;

	@GetMapping(AUDITS_BASE + "/{id}")
	@PreAuthorize("hasAuthority('admin.audit.read')")
	String auditDetailPage(@PathVariable long id, Model model) {
		populateDetailModel(model, auditContext(), id);
		return "admin/audits/detail";
	}

	// 상세 드로어(#1747): 같은 URL을 htmx로 열면 상세 본문 fragment만 반환하고 HX-Trigger로
	// admin-drawer-open을 쏴 패널을 연다. no-JS에서는 상세 링크가 상세 페이지로 이동한다.
	@HxRequest
	@GetMapping(AUDITS_BASE + "/{id}")
	@HxTrigger("admin-drawer-open")
	@PreAuthorize("hasAuthority('admin.audit.read')")
	String auditDetailDrawer(@PathVariable long id, Model model) {
		populateDetailModel(model, auditContext(), id);
		return "admin/audits/detail :: detailBody";
	}

	@GetMapping(PRIVACY_BASE + "/{id}")
	@PreAuthorize("hasAuthority('admin.privacy-log.read')")
	String privacyDetailPage(@PathVariable long id, Model model) {
		populateDetailModel(model, privacyContext(), id);
		return "admin/audits/detail";
	}

	@HxRequest
	@GetMapping(PRIVACY_BASE + "/{id}")
	@HxTrigger("admin-drawer-open")
	@PreAuthorize("hasAuthority('admin.privacy-log.read')")
	String privacyDetailDrawer(@PathVariable long id, Model model) {
		populateDetailModel(model, privacyContext(), id);
		return "admin/audits/detail :: detailBody";
	}

	private void populateDetailModel(Model model, ScreenContext context, long id) {
		AdminAuditEvent event = auditEventRepository
			.findById(id, context.forcedEventType(), context.excludePrivacyRead())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "감사 이벤트를 찾을 수 없습니다."));
		AdminAuditActorContext timeline = auditEventRepository.findActorContext(
			event, context.forcedEventType(), context.excludePrivacyRead(), TIMELINE_RADIUS);

		model.addAttribute("title", context.title());
		model.addAttribute("activeProgram", context.activeProgram());
		model.addAttribute("basePath", context.path());
		model.addAttribute("privacyMode", context.privacyMode());
		model.addAttribute("event", AuditEventRow.from(event));
		model.addAttribute("targetHref", AuditTargetLink.hrefFor(event.targetType(), event.targetId()));
		model.addAttribute("timelineBefore", timeline.before().stream().map(AuditEventRow::from).toList());
		model.addAttribute("timelineAfter", timeline.after().stream().map(AuditEventRow::from).toList());
	}

	private static ScreenContext auditContext() {
		// 관리자 감사 화면은 개인정보 조회(PRIVACY_READ)를 제외한다 — 개인정보 로그는 별도 권한의 전용
		// 화면에서만 본다(AUDIT_READ로는 개인정보 조회 이력·사유·전후 흐름에 접근 불가).
		return new ScreenContext("관리자 감사", "a-audits", AUDITS_PATH, AUDITS_BASE, null, false, true);
	}

	private static ScreenContext privacyContext() {
		return new ScreenContext(
			"개인정보 조회 로그", "a-privacy-audits", PRIVACY_PATH, PRIVACY_BASE,
			AdminAuditEventType.PRIVACY_READ, true, false);
	}

	private void populateAuditModel(Model model, ScreenContext context, AuditFilterParams params) {
		// 목록·내보내기가 공유하는 바운드 질의(AdminAuditQuery가 size를 MAX_SIZE로 캡한다).
		AdminAuditQuery query = AdminAuditQuery.of(
			context.forcedEventType(),
			params.eventTypeOrNull(),
			params.actor(),
			params.outcomeOrNull(),
			params.keyword(),
			params.from(),
			params.to(),
			params.reasonMissing(),
			params.page(),
			params.size(),
			context.excludePrivacyRead()
		);

		long total = auditEventRepository.count(query);
		EgovPaginationView pageView = EgovPaginationView.from(query.page(), query.size(), total);
		AdminAuditQuery pageQuery = query.withPage(pageView.page());
		List<AuditEventRow> events = auditEventRepository.search(pageQuery).stream()
			.map(AuditEventRow::from)
			.toList();

		model.addAttribute("title", context.title());
		model.addAttribute("paginationLabel", context.title() + " 페이지");
		model.addAttribute("activeProgram", context.activeProgram());
		model.addAttribute("basePath", context.path());
		model.addAttribute("detailBase", context.detailBase());
		model.addAttribute("exportPath", context.detailBase() + "/export");
		model.addAttribute("privacyMode", context.privacyMode());
		model.addAttribute("events", events);
		model.addAttribute("total", total);
		model.addAttribute("page", pageView);
		model.addAttribute("paginationLinks", pageView.links(context.path(), filterParams(pageQuery)));

		// 필터 툴바 상태·옵션.
		model.addAttribute("selectedEventType", pageQuery.eventType());
		model.addAttribute("selectedActor", pageQuery.actor());
		model.addAttribute("selectedOutcome", pageQuery.outcome());
		model.addAttribute("keyword", pageQuery.targetKeyword());
		model.addAttribute("from", pageQuery.occurredFrom());
		model.addAttribute("to", pageQuery.occurredTo());
		model.addAttribute("reasonMissing", pageQuery.reasonMissing());
		model.addAttribute("eventTypeOptions", eventTypeOptions(pageQuery.eventType()));
		model.addAttribute("outcomeOptions", outcomeOptions(pageQuery.outcome()));
		model.addAttribute("actorOptions", actorOptions(context.forcedEventType(), pageQuery.actor()));

		// 개인정보 로그 컴플라이언스 신호(#1747): 같은 필터에서 "조회 사유가 비어 있는" 건수를 세어
		// 점검 배너로 노출하고, 그 조건만 거르는 빠른 필터 링크를 제공한다.
		if (context.privacyMode()) {
			long reasonMissingCount = pageQuery.reasonMissing()
				? total
				: auditEventRepository.count(withReasonMissing(pageQuery));
			model.addAttribute("reasonMissingCount", reasonMissingCount);
			model.addAttribute("reasonMissingHref",
				context.path() + reasonMissingLinkQuery(pageQuery));
		}
	}

	private static AdminAuditQuery withReasonMissing(AdminAuditQuery query) {
		return new AdminAuditQuery(
			query.eventType(), query.actor(), query.outcome(), query.targetKeyword(),
			query.occurredFrom(), query.occurredTo(), true, 0, query.size(), query.excludePrivacyRead());
	}

	// 현재 필터를 유지하며 reasonMissing=true만 추가한 목록 링크.
	private static String reasonMissingLinkQuery(AdminAuditQuery query) {
		Map<String, Object> params = new LinkedHashMap<>(filterParams(query));
		params.put("reasonMissing", "true");
		org.springframework.web.util.UriComponentsBuilder builder =
			org.springframework.web.util.UriComponentsBuilder.newInstance();
		params.forEach((name, value) -> {
			if (value != null && !value.toString().isBlank()) {
				builder.queryParam(name, value);
			}
		});
		return builder.build().encode().toUriString();
	}

	// 페이지네이션·필터 링크가 현재 필터를 유지하도록 활성 파라미터만 전달한다(널·빈·거짓 값 생략).
	private static Map<String, Object> filterParams(AdminAuditQuery query) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("eventType", query.eventType() == null ? null : query.eventType().name());
		params.put("actor", query.actor());
		params.put("outcome", query.outcome() == null ? null : query.outcome().name());
		params.put("keyword", query.targetKeyword());
		params.put("from", query.occurredFrom());
		params.put("to", query.occurredTo());
		if (query.reasonMissing()) {
			params.put("reasonMissing", "true");
		}
		return params;
	}

	private List<FilterOption> actorOptions(AdminAuditEventType scopeEventType, String selected) {
		List<FilterOption> options = new ArrayList<>();
		options.add(new FilterOption("", "actor 전체", selected == null));
		for (String actor : auditEventRepository.findDistinctActors(scopeEventType)) {
			options.add(new FilterOption(actor, actor, actor.equals(selected)));
		}
		return options;
	}

	private static List<FilterOption> eventTypeOptions(AdminAuditEventType selected) {
		List<FilterOption> options = new ArrayList<>();
		options.add(new FilterOption("", "유형 전체", selected == null));
		for (AdminAuditEventType type : AdminAuditEventType.values()) {
			// 유형 필터는 관리자 감사 화면에서만 노출된다. 개인정보 조회는 전용 화면에서만 보므로 제외한다.
			if (type == AdminAuditEventType.PRIVACY_READ) {
				continue;
			}
			options.add(new FilterOption(type.name(), AuditLabels.eventType(type), type == selected));
		}
		return options;
	}

	private static List<FilterOption> outcomeOptions(AdminAuditOutcome selected) {
		List<FilterOption> options = new ArrayList<>();
		options.add(new FilterOption("", "결과 전체", selected == null));
		for (AdminAuditOutcome outcome : AdminAuditOutcome.values()) {
			options.add(new FilterOption(outcome.name(), AuditLabels.outcome(outcome), outcome == selected));
		}
		return options;
	}

	record FilterOption(String value, String label, boolean selected) {
	}

	private record ScreenContext(
		String title,
		String activeProgram,
		String path,
		String detailBase,
		AdminAuditEventType forcedEventType,
		boolean privacyMode,
		boolean excludePrivacyRead
	) {
	}

	record AuditEventRow(
		Long id,
		String eventType,
		String eventTypeLabel,
		String actor,
		String rolePermission,
		String requestId,
		String clientIp,
		String userAgent,
		String targetType,
		String targetId,
		String action,
		String outcome,
		String outcomeLabel,
		String outcomeTone,
		String reason,
		boolean reasonMissing,
		String occurredAt
	) {

		static AuditEventRow from(AdminAuditEvent event) {
			return new AuditEventRow(
				event.id(),
				event.eventType().name(),
				AuditLabels.eventType(event.eventType()),
				event.actor(),
				orDash(event.rolePermission()),
				orDash(event.requestId()),
				orDash(event.clientIp()),
				orDash(event.userAgent()),
				event.targetType(),
				orDash(event.targetId()),
				event.action(),
				event.outcome().name(),
				AuditLabels.outcome(event.outcome()),
				event.outcome() == AdminAuditOutcome.FAILURE ? "failure" : "ok",
				orDash(event.reason()),
				// 사유 누락은 표시 문자열("-")이 아니라 실제 도메인 값(null·공백)으로 판정한다.
				event.reason() == null || event.reason().isBlank(),
				event.occurredAt().toString()
			);
		}

		private static String orDash(String value) {
			return value == null || value.isBlank() ? "-" : value;
		}
	}
}
