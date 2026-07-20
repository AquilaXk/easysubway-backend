package com.easysubway.transit.adapter.in.web;

import com.easysubway.admin.authorization.AdminAuthorization;
import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.web.AdminFormErrorView;
import com.easysubway.admin.web.AdminMasterLabelResolver;
import com.easysubway.common.web.WebMessageResolver;
import com.easysubway.common.web.pagination.AdminPageRequest;
import com.easysubway.common.web.pagination.EgovPaginationView;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase;
import com.easysubway.field.application.port.in.FieldVerificationUseCase;
import com.easysubway.field.domain.FieldVerificationChangeHistory;
import com.easysubway.field.domain.FieldVerificationSession;
import com.easysubway.field.domain.FieldVerificationStatus;
import com.easysubway.report.application.port.in.FacilityReportListQuery;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.transit.application.port.in.CreateAccessibilityFacilityCommand;
import com.easysubway.transit.application.port.in.StationMasterDataCounts;
import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.TransitMasterAdminUseCase;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityCommand;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.MasterDataWriteNotAllowedException;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationWithLines;
import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxRequest;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

@Controller
class TransitStationAdminPageController {

	// 역 상세 허브 탭(#1741). no-JS는 ?tab= 링크로 전체 페이지, htmx는 tabPanel fragment만 부분 갱신한다.
	static final String TAB_OVERVIEW = "overview";
	static final String TAB_FACILITIES = "facilities";
	static final String TAB_REPORTS = "reports";
	static final String TAB_FIELD = "field";
	static final String TAB_STRUCTURE = "structure";
	private static final List<String> STATION_HUB_TABS = List.of(
		TAB_OVERVIEW, TAB_FACILITIES, TAB_REPORTS, TAB_FIELD, TAB_STRUCTURE);
	// 제보 이력 탭은 최근 N건만 보여주고 전체는 필터된 제보 목록으로 딥링크한다(query budget 보호).
	private static final int STATION_HUB_REPORT_LIMIT = 10;

	private final TransitMasterQueryUseCase transitMasterQueryUseCase;
	private final TransitMasterAdminUseCase transitMasterAdminUseCase;
	private final DatapackReleaseBlockerSummaryUseCase datapackReleaseBlockerSummaryUseCase;
	private final FacilityReportUseCase facilityReportUseCase;
	private final FieldVerificationUseCase fieldVerificationUseCase;
	private final AdminMasterLabelResolver labelResolver;
	private final WebMessageResolver messages;

	TransitStationAdminPageController(
		TransitMasterQueryUseCase transitMasterQueryUseCase,
		TransitMasterAdminUseCase transitMasterAdminUseCase,
		DatapackReleaseBlockerSummaryUseCase datapackReleaseBlockerSummaryUseCase,
		FacilityReportUseCase facilityReportUseCase,
		FieldVerificationUseCase fieldVerificationUseCase,
		AdminMasterLabelResolver labelResolver,
		WebMessageResolver messages
	) {
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
		this.transitMasterAdminUseCase = transitMasterAdminUseCase;
		this.datapackReleaseBlockerSummaryUseCase = datapackReleaseBlockerSummaryUseCase;
		this.facilityReportUseCase = facilityReportUseCase;
		this.fieldVerificationUseCase = fieldVerificationUseCase;
		this.labelResolver = labelResolver;
		this.messages = messages;
	}

	@GetMapping("/admin/stations/page")
	String stationsPage(
		@RequestParam(required = false) String query,
		@RequestParam(required = false) String region,
		@RequestParam(required = false) String lineId,
		@RequestParam(required = false) String sort,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		populateStationList(query, region, lineId, sort, page, size, model);
		return "admin/stations/list";
	}

	// 역 목록 표준 테이블(#1741): 검색은 htmx 부분 갱신, no-JS는 form GET. 같은 모델·같은 필터를 공유한다.
	@HxRequest
	@GetMapping("/admin/stations/page")
	String stationsListFragment(
		@RequestParam(required = false) String query,
		@RequestParam(required = false) String region,
		@RequestParam(required = false) String lineId,
		@RequestParam(required = false) String sort,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		@RequestHeader(value = "HX-History-Restore-Request", required = false) boolean historyRestore,
		Model model
	) {
		if (historyRestore) {
			return stationsPage(query, region, lineId, sort, page, size, model);
		}
		populateStationList(query, region, lineId, sort, page, size, model);
		return "admin/stations/list :: stationResults";
	}

	private void populateStationList(
		String query,
		String region,
		String lineId,
		String sort,
		Integer page,
		Integer size,
		Model model
	) {
		AdminPageRequest pageRequest = AdminPageRequest.of(page, size);
		Map<String, StationMasterDataCounts> counts = transitMasterQueryUseCase.countStationMasterDataByStationId();
		Map<String, Long> pendingByStation = facilityReportUseCase.countPendingReportsByStation();
		// 확인 필요 시설 뱃지: 시설 전체 1회 벌크 집계(needsAttention). 시설 상태판과 같은 도메인 판정으로 정합.
		Map<String, Long> attentionByStation = transitMasterQueryUseCase.countAttentionFacilitiesByStation();
		// 검색만 서버에서 걸고(searchStations), 지역·노선 필터·정렬은 결과 위에서 적용해 조회를 1회로 유지한다.
		List<StationWithLines> matched = transitMasterQueryUseCase.searchStations(new StationSearchCommand(query, null));
		// 필터 옵션(지역·노선)은 노선/지역 필터가 걸리기 전 결과에서 뽑아 항상 전체 후보를 보여준다.
		model.addAttribute("regionOptions", distinctRegions(matched, region));
		model.addAttribute("lineOptions", distinctLines(matched, lineId));

		String activeRegion = blankToNull(region);
		String activeLine = blankToNull(lineId);
		List<StationRow> stations = matched.stream()
			.filter(station -> activeRegion == null || activeRegion.equals(station.station().region()))
			.filter(station -> activeLine == null
				|| station.lines().stream().anyMatch(line -> activeLine.equals(line.id())))
			.map(station -> StationRow.from(
				station,
				counts.getOrDefault(station.station().id(), StationMasterDataCounts.empty()),
				pendingByStation.getOrDefault(station.station().id(), 0L),
				attentionByStation.getOrDefault(station.station().id(), 0L)))
			.sorted(stationOrder(sort))
			.toList();

		EgovPaginationView pageView = EgovPaginationView.from(pageRequest.page(), pageRequest.size(), stations.size());
		model.addAttribute("stations", pageView.pageItems(stations));
		model.addAttribute("page", pageView);
		Map<String, Object> linkParams = new java.util.LinkedHashMap<>();
		linkParams.put("query", query);
		linkParams.put("region", activeRegion);
		linkParams.put("lineId", activeLine);
		linkParams.put("sort", blankToNull(sort));
		model.addAttribute("paginationLinks", pageView.links("/admin/stations/page", linkParams));
		model.addAttribute("query", query);
		model.addAttribute("selectedRegion", activeRegion);
		model.addAttribute("selectedLine", activeLine);
		model.addAttribute("selectedSort", blankToNull(sort));
	}

	private static List<FilterOption> distinctRegions(List<StationWithLines> stations, String selected) {
		List<FilterOption> options = new java.util.ArrayList<>();
		options.add(new FilterOption("", "전체 지역", blankToNull(selected) == null));
		stations.stream()
			.map(station -> station.station().region())
			.filter(value -> value != null && !value.isBlank())
			.distinct()
			.sorted()
			.forEach(value -> options.add(new FilterOption(value, value, value.equals(selected))));
		return options;
	}

	private static List<FilterOption> distinctLines(List<StationWithLines> stations, String selected) {
		List<FilterOption> options = new java.util.ArrayList<>();
		options.add(new FilterOption("", "전체 노선", blankToNull(selected) == null));
		stations.stream()
			.flatMap(station -> station.lines().stream())
			.collect(java.util.stream.Collectors.toMap(line -> line.id(), line -> line.name(), (a, b) -> a,
				java.util.LinkedHashMap::new))
			.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByValue())
			.forEach(entry -> options.add(
				new FilterOption(entry.getKey(), entry.getValue(), entry.getKey().equals(selected))));
		return options;
	}

	// 정렬: 미확인 제보 많은 순(pending)·역명 오름/내림차순. 기본은 역명 오름차순.
	private static java.util.Comparator<StationRow> stationOrder(String sort) {
		String value = sort == null ? "" : sort;
		return switch (value) {
			case "pending" -> java.util.Comparator.comparingLong(StationRow::pendingReportCount).reversed()
				.thenComparing(StationRow::stationName);
			case "name_desc" -> java.util.Comparator.comparing(StationRow::stationName).reversed();
			default -> java.util.Comparator.comparing(StationRow::stationName);
		};
	}

	@GetMapping("/admin/stations/{stationId}/page")
	String stationDetailPage(
		@PathVariable String stationId,
		@RequestParam(required = false) String tab,
		Model model,
		Authentication authentication
	) {
		populateStationHub(stationId, tab, model, authentication);
		return "admin/stations/detail";
	}

	// 허브 탭 전환(#1741): 같은 URL을 htmx로 열면 tabPanel fragment만 반환한다. 히스토리 복원은 풀페이지.
	@HxRequest
	@GetMapping("/admin/stations/{stationId}/page")
	String stationDetailTab(
		@PathVariable String stationId,
		@RequestParam(required = false) String tab,
		@RequestHeader(value = "HX-History-Restore-Request", required = false) boolean historyRestore,
		Model model,
		Authentication authentication
	) {
		if (historyRestore) {
			return stationDetailPage(stationId, tab, model, authentication);
		}
		populateStationHub(stationId, tab, model, authentication);
		return "admin/stations/detail :: tabPanel";
	}

	private void populateStationHub(String stationId, String tab, Model model, Authentication authentication) {
		String activeTab = tab != null && STATION_HUB_TABS.contains(tab) ? tab : TAB_OVERVIEW;
		StationWithLines station = transitMasterQueryUseCase.getStation(stationId);
		model.addAttribute("station", StationDetail.from(station));
		model.addAttribute("activeTab", activeTab);
		long pendingReportCount = countPendingStationReports(stationId);
		model.addAttribute("pendingReportCount", pendingReportCount);
		model.addAttribute("tabs", stationHubTabs(stationId, activeTab, pendingReportCount));
		switch (activeTab) {
			case TAB_FACILITIES -> populateFacilitiesTab(stationId, model);
			case TAB_REPORTS -> populateReportsTab(stationId, model);
			case TAB_FIELD -> populateFieldTab(stationId, model);
			case TAB_STRUCTURE -> populateStructureTab(stationId, model);
			default -> populateOverviewTab(stationId, model, authentication);
		}
	}

	private List<StationHubTab> stationHubTabs(String stationId, String activeTab, long pendingReportCount) {
		return List.of(
			stationHubTab(stationId, TAB_OVERVIEW, "개요", activeTab, null),
			stationHubTab(stationId, TAB_FACILITIES, "시설", activeTab, null),
			stationHubTab(stationId, TAB_REPORTS, "제보 이력", activeTab,
				pendingReportCount > 0 ? pendingReportCount : null),
			stationHubTab(stationId, TAB_FIELD, "현장 확인", activeTab, null),
			stationHubTab(stationId, TAB_STRUCTURE, "구조·동선", activeTab, null)
		);
	}

	private static StationHubTab stationHubTab(
		String stationId,
		String token,
		String label,
		String activeTab,
		Long badge
	) {
		String href = "/admin/stations/%s/page?tab=%s".formatted(stationId, token);
		return new StationHubTab(token, label, href, token.equals(activeTab), badge);
	}

	private void populateOverviewTab(String stationId, Model model, Authentication authentication) {
		model.addAttribute("facilityCount", transitMasterQueryUseCase.listStationFacilities(stationId).size());
		model.addAttribute("layoutSourceCount", transitMasterQueryUseCase.listStationLayoutSources(stationId).size());
		model.addAttribute("routeNodeCount", transitMasterQueryUseCase.listRouteNodes(stationId).size());
		model.addAttribute("routeEdgeCount", transitMasterQueryUseCase.listRouteEdges(stationId).size());
		if (AdminAuthorization.hasPermission(authentication, AdminPermission.DATAPACK_READ)) {
			model.addAttribute("stationReleaseSummary", datapackReleaseBlockerSummaryUseCase.summarizeStation(stationId));
		}
	}

	private void populateFacilitiesTab(String stationId, Model model) {
		model.addAttribute("exits", transitMasterQueryUseCase.listStationExits(stationId).stream()
			.map(ExitRow::from)
			.toList());
		model.addAttribute("facilities", transitMasterQueryUseCase.listStationFacilities(stationId).stream()
			.map(FacilityRow::from)
			.toList());
	}

	private void populateReportsTab(String stationId, Model model) {
		FacilityReportListQuery query = FacilityReportListQuery.of(
			null, null, stationId, null, null, null, null, null, 0, STATION_HUB_REPORT_LIMIT);
		List<FacilityReportSummary> items = facilityReportUseCase.searchReportSummaries(query).items();
		Map<String, String> facilityLabels = labelResolver.facilityLabels(
			items.stream().map(FacilityReportSummary::facilityId).toList());
		model.addAttribute("stationReports", items.stream()
			.map(summary -> StationReportRow.from(summary, messages, facilityLabels))
			.toList());
		// 전체 제보는 역 필터가 걸린 제보 대기열로 딥링크한다(#1740 station 필터 재사용).
		model.addAttribute("stationReportsHref", "/admin/reports/page?station=" + stationId);
	}

	private void populateFieldTab(String stationId, Model model) {
		// getStationVerification은 세션이 없으면 예외라, 목록에서 걸러 Optional로 안전하게 처리한다.
		Optional<FieldVerificationSession> session = fieldVerificationUseCase.listStationVerifications().stream()
			.filter(candidate -> candidate.stationId().equals(stationId))
			.findFirst();
		model.addAttribute("fieldSession", session.map(StationFieldSession::from).orElse(null));
		model.addAttribute("fieldHistory", session.isEmpty() ? List.of()
			: fieldVerificationUseCase.listStationChangeHistory(stationId).stream()
				.limit(5)
				.map(StationFieldHistoryRow::from)
				.toList());
		model.addAttribute("fieldVerificationHref", "/admin/field-verifications/" + stationId + "/page");
	}

	private void populateStructureTab(String stationId, Model model) {
		model.addAttribute("layoutSources", transitMasterQueryUseCase.listStationLayoutSources(stationId).stream()
			.map(LayoutSourceRow::from)
			.toList());
		model.addAttribute("routeNodes", transitMasterQueryUseCase.listRouteNodes(stationId).stream()
			.map(RouteNodeRow::from)
			.toList());
		model.addAttribute("routeEdges", transitMasterQueryUseCase.listRouteEdges(stationId).stream()
			.map(RouteEdgeRow::from)
			.toList());
		model.addAttribute("layoutEditorHref", "/admin/stations/%s/layouts/page".formatted(stationId));
	}

	private long countPendingStationReports(String stationId) {
		return countStationReports(stationId, FacilityReportStatus.SUBMITTED)
			+ countStationReports(stationId, FacilityReportStatus.UNDER_REVIEW);
	}

	private long countStationReports(String stationId, FacilityReportStatus status) {
		return facilityReportUseCase.countReports(
			FacilityReportListQuery.of(status, null, stationId, null, null, null, null, null, 0, 1));
	}

	@GetMapping("/admin/facilities/editor/page")
	String facilityEditorPage(
		@RequestParam(required = false) String stationId,
		@RequestParam(required = false) String facilityId,
		Model model
	) {
		populateFacilityEditorModel(model, stationId, facilityId, null);
		return "admin/facilities/editor";
	}

	private void populateFacilityEditorModel(
		Model model,
		String stationId,
		String facilityId,
		FacilityEditorForm submittedForm
	) {
		List<StationWithLines> stations = transitMasterQueryUseCase.searchStations(new StationSearchCommand(null, null));
		String selectedStationId = stationId == null && !stations.isEmpty() ? stations.getFirst().station().id() : stationId;
		List<FacilityRow> facilities = selectedStationId == null ? List.of() : transitMasterQueryUseCase
			.listStationFacilities(selectedStationId)
			.stream()
			.map(FacilityRow::from)
			.toList();
		FacilityRow selectedFacility = "__new".equals(facilityId) ? null : facilities.stream()
			.filter(facility -> facility.facilityId().equals(facilityId))
			.findFirst()
			.orElse(facilities.isEmpty() ? null : facilities.getFirst());
		model.addAttribute("stations", stations.stream().map(StationOption::from).toList());
		model.addAttribute("selectedStationId", selectedStationId);
		model.addAttribute("facilities", facilities);
		model.addAttribute("selectedFacility", selectedFacility);
		model.addAttribute("typeOptions", Arrays.asList(AccessibilityFacilityType.values()));
		model.addAttribute("statusOptions", Arrays.asList(AccessibilityFacilityStatus.values()));
		model.addAttribute("confidenceOptions", Arrays.asList(DataConfidenceLevel.values()));
		model.addAttribute("sourceTypeOptions", Arrays.asList(DataSourceType.values()));
		model.addAttribute("masterDataWritable", transitMasterAdminUseCase.masterDataCapability().writable());
		model.addAttribute("facilityForm", submittedForm == null
			? FacilityEditorForm.from(selectedStationId, selectedFacility)
			: submittedForm);
	}

	@PostMapping("/admin/facilities/editor/page")
	@PreAuthorize("hasAuthority('admin.master.edit')")
	String saveFacilityFromPage(
		@Valid @ModelAttribute("facilityForm") FacilityEditorForm form,
		BindingResult bindingResult,
		Principal principal,
		RedirectAttributes redirectAttributes,
		Model model,
		HttpServletResponse response
	) {
		if (bindingResult.hasErrors()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			populateFacilityEditorModel(model, form.stationId(), form.facilityId(), form);
			model.addAttribute("facilityLatitudeValue", bindingResult.getFieldValue("latitude"));
			model.addAttribute("facilityLongitudeValue", bindingResult.getFieldValue("longitude"));
			AdminFormErrorView.expose(model, bindingResult);
			return "admin/facilities/editor";
		}
		try {
			if (form.facilityId() == null || form.facilityId().isBlank()) {
				String newFacilityId = "facility-" + form.stationId() + "-" + form.type().name().toLowerCase() + "-" + System.currentTimeMillis();
				transitMasterAdminUseCase.createAccessibilityFacility(new CreateAccessibilityFacilityCommand(
					newFacilityId,
					form.stationId(),
					blankToNull(form.exitId()),
					form.type(),
					form.name(),
					blankToNull(form.floorFrom()),
					blankToNull(form.floorTo()),
					form.latitude(),
					form.longitude(),
					blankToNull(form.description()),
					form.status(),
					form.dataConfidence(),
					form.dataSourceType(),
					principal.getName()
				));
				return "redirect:/admin/facilities/editor/page?stationId=%s&facilityId=%s".formatted(form.stationId(), newFacilityId);
			}
			transitMasterAdminUseCase.updateAccessibilityFacility(new UpdateAccessibilityFacilityCommand(
				form.facilityId(),
				form.stationId(),
				blankToNull(form.exitId()),
				form.type(),
				form.name(),
				blankToNull(form.floorFrom()),
				blankToNull(form.floorTo()),
				form.latitude(),
				form.longitude(),
				blankToNull(form.description()),
				form.status(),
				form.dataConfidence(),
				form.dataSourceType(),
				principal.getName()
			));
		} catch (MasterDataWriteNotAllowedException exception) {
			redirectAttributes.addFlashAttribute("masterDataError", exception.getMessage());
		}
		return "redirect:/admin/facilities/editor/page?stationId=%s&facilityId=%s".formatted(form.stationId(), form.facilityId());
	}

	private static String qualityLabel(DataQualityLevel level) {
		return level.label();
	}

	private static String confidenceLabel(DataConfidenceLevel level) {
		return switch (level) {
			case HIGH -> "확인된 정보";
			case MEDIUM -> "일부 확인된 정보";
			case LOW -> "확인이 더 필요한 정보";
			case NEEDS_VERIFICATION -> "확인 필요";
		};
	}

	private static String statusLabel(AccessibilityFacilityStatus status) {
		return FacilityStatusRow.statusLabel(status);
	}

	record StationRow(
		String stationId,
		String stationName,
		String lineNames,
		String region,
		String qualityLabel,
		String lastVerifiedAt,
		int exitCount,
		int facilityCount,
		int layoutSourceCount,
		int routeNodeCount,
		int routeEdgeCount,
		long pendingReportCount,
		long attentionFacilityCount
	) {

		static StationRow from(
			StationWithLines stationWithLines,
			StationMasterDataCounts counts,
			long pendingReportCount,
			long attentionFacilityCount
		) {
			return new StationRow(
				stationWithLines.station().id(),
				stationWithLines.station().nameKo(),
				stationWithLines.lines().stream().map(line -> line.name()).reduce((a, b) -> a + ", " + b).orElse("—"),
				stationWithLines.station().region(),
				TransitStationAdminPageController.qualityLabel(stationWithLines.station().dataQualityLevel()),
				String.valueOf(stationWithLines.station().lastVerifiedAt()),
				counts.exitCount(),
				counts.facilityCount(),
				counts.layoutSourceCount(),
				counts.routeNodeCount(),
				counts.routeEdgeCount(),
				pendingReportCount,
				attentionFacilityCount
			);
		}
	}

	// 역 목록 필터 select 옵션(지역·노선). value가 빈 문자열이면 "전체".
	record FilterOption(String value, String label, boolean selected) {
	}

	record StationDetail(
		String stationId,
		String stationName,
		String lineNames,
		String region,
		String latitude,
		String longitude,
		String qualityLabel,
		String sourceType,
		String lastVerifiedAt
	) {

		static StationDetail from(StationWithLines stationWithLines) {
			return new StationDetail(
				stationWithLines.station().id(),
				stationWithLines.station().nameKo(),
				stationWithLines.lines().stream().map(line -> line.name()).reduce((a, b) -> a + ", " + b).orElse("—"),
				stationWithLines.station().region(),
				String.valueOf(stationWithLines.station().latitude()),
				String.valueOf(stationWithLines.station().longitude()),
				TransitStationAdminPageController.qualityLabel(stationWithLines.station().dataQualityLevel()),
				stationWithLines.station().dataSourceType().label(),
				String.valueOf(stationWithLines.station().lastVerifiedAt())
			);
		}
	}

	record StationOption(String stationId, String stationName) {

		static StationOption from(StationWithLines stationWithLines) {
			return new StationOption(stationWithLines.station().id(), stationWithLines.station().nameKo());
		}
	}

	record FacilityEditorForm(
		String facilityId,
		@NotBlank(message = "{validation.transit.station-id.required}")
		String stationId,
		String exitId,
		@NotNull(message = "{validation.transit.facility-type.required}")
		AccessibilityFacilityType type,
		@NotBlank(message = "{validation.transit.facility-name.required}")
		String name,
		String floorFrom,
		String floorTo,
		BigDecimal latitude,
		BigDecimal longitude,
		String description,
		@NotNull(message = "{validation.transit.facility-status.required}")
		AccessibilityFacilityStatus status,
		@NotNull(message = "{validation.transit.facility-confidence.required}")
		DataConfidenceLevel dataConfidence,
		@NotNull(message = "{validation.transit.facility-source-type.required}")
		DataSourceType dataSourceType
	) {

		static FacilityEditorForm from(String selectedStationId, FacilityRow facility) {
			if (facility == null) {
				return new FacilityEditorForm(
					"",
					selectedStationId,
					"",
					null,
					"",
					"",
					"",
					null,
					null,
					"",
					null,
					null,
					null
				);
			}
			return new FacilityEditorForm(
				facility.facilityId(),
				selectedStationId,
				facility.exitId(),
				facility.type(),
				facility.facilityName(),
				facility.floorFrom(),
				facility.floorTo(),
				blankToBigDecimal(facility.latitude()),
				blankToBigDecimal(facility.longitude()),
				facility.description(),
				facility.status(),
				facility.dataConfidence(),
				facility.dataSourceType()
			);
		}
	}

	record ExitRow(String exitNumber, String name, String elevatorLabel, String stairOnlyLabel, String confidenceLabel) {

		static ExitRow from(StationExit exit) {
			return new ExitRow(
				exit.exitNumber(),
				exit.name(),
				exit.hasElevatorConnection() ? "엘리베이터 연결" : "엘리베이터 미확인",
				exit.hasStairOnlyPath() ? "계단 전용 경로 있음" : "계단 전용 아님",
				TransitStationAdminPageController.confidenceLabel(exit.dataConfidence())
			);
		}
	}

	record FacilityRow(
		String facilityId,
		String facilityName,
		AccessibilityFacilityType type,
		String typeLabel,
		String exitId,
		String floorFrom,
		String floorTo,
		String floorLabel,
		AccessibilityFacilityStatus status,
		String statusLabel,
		DataConfidenceLevel dataConfidence,
		String confidenceLabel,
		DataSourceType dataSourceType,
		String latitude,
		String longitude,
		String lastUpdatedAt,
		String description
	) {

		static FacilityRow from(AccessibilityFacility facility) {
			return new FacilityRow(
				facility.id(),
				facility.name(),
				facility.type(),
				facility.type().label(),
				facility.exitId(),
				facility.floorFrom(),
				facility.floorTo(),
				facility.floorFrom() + " → " + facility.floorTo(),
				facility.status(),
				TransitStationAdminPageController.statusLabel(facility.status()),
				facility.dataConfidence(),
				TransitStationAdminPageController.confidenceLabel(facility.dataConfidence()),
				facility.dataSourceType(),
				TransitStationAdminPageController.optionalText(facility.latitude()),
				TransitStationAdminPageController.optionalText(facility.longitude()),
				String.valueOf(facility.lastUpdatedAt()),
				facility.description()
			);
		}
	}

	private static String optionalText(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static BigDecimal blankToBigDecimal(String value) {
		return value == null || value.isBlank() ? null : new BigDecimal(value);
	}

	record LayoutSourceRow(String sourceName, String sourceType, String license) {

		static LayoutSourceRow from(StationLayoutSource source) {
			return new LayoutSourceRow(source.sourceName(), source.sourceType().name(), source.license());
		}
	}

	record RouteNodeRow(String nodeName, String nodeType, String floor) {

		static RouteNodeRow from(RouteNode node) {
			return new RouteNodeRow(node.name(), node.type().label(), node.floor());
		}
	}

	record RouteEdgeRow(String edgeName, String edgeType, int confidenceScore) {

		static RouteEdgeRow from(RouteEdge edge) {
			return new RouteEdgeRow(
				edge.fromNodeId() + " → " + edge.toNodeId(),
				edge.type().label(),
				edge.reliabilityScore()
			);
		}
	}

	// 역 상세 허브 탭 네비 항목(#1741). badge는 값이 있을 때만 노출(예: 제보 이력의 대기 건수).
	record StationHubTab(String token, String label, String href, boolean active, Long badge) {

		public boolean hasBadge() {
			return badge != null;
		}
	}

	// 제보 이력 탭 행(#1741): 제보 대기열(#1740) 요약을 이름(코드)·라벨로 재사용한다.
	record StationReportRow(
		String reportId,
		String facilityLabel,
		String typeLabel,
		String statusLabel,
		LocalDateTime createdAt,
		boolean hasPhoto,
		String href
	) {

		static StationReportRow from(
			FacilityReportSummary summary,
			WebMessageResolver messages,
			Map<String, String> facilityLabels
		) {
			return new StationReportRow(
				summary.id(),
				facilityLabels.getOrDefault(summary.facilityId(), summary.facilityId()),
				messages.enumLabel("admin.report.type", summary.reportType()),
				messages.enumLabel("admin.report.status", summary.status()),
				summary.createdAt(),
				summary.hasPhoto(),
				"/admin/reports/%s/page".formatted(summary.id())
			);
		}
	}

	// 현장 확인 탭 세션 요약(#1741).
	record StationFieldSession(
		String stationName,
		String verifiedAt,
		String verifiedBy,
		String statusLabel,
		String note,
		int itemCount
	) {

		static StationFieldSession from(FieldVerificationSession session) {
			return new StationFieldSession(
				session.stationName(),
				String.valueOf(session.verifiedAt()),
				session.verifiedBy(),
				TransitStationAdminPageController.fieldStatusLabel(session.status()),
				session.note(),
				session.items().size()
			);
		}
	}

	// 현장 확인 탭 상태 변경 이력 행(#1741).
	record StationFieldHistoryRow(
		String itemId,
		String previousStatusLabel,
		String newStatusLabel,
		String changedBy,
		LocalDateTime changedAt
	) {

		static StationFieldHistoryRow from(FieldVerificationChangeHistory history) {
			return new StationFieldHistoryRow(
				history.itemId(),
				TransitStationAdminPageController.fieldStatusLabel(history.previousStatus()),
				TransitStationAdminPageController.fieldStatusLabel(history.newStatus()),
				history.changedBy(),
				history.changedAt()
			);
		}
	}

	private static String fieldStatusLabel(FieldVerificationStatus status) {
		return switch (status) {
			case PLANNED -> "예정";
			case IN_PROGRESS -> "진행 중";
			case VERIFIED -> "확인 완료";
			case NEEDS_RECHECK -> "다시 확인 필요";
		};
	}
}
