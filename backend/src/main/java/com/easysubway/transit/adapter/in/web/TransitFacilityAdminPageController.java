package com.easysubway.transit.adapter.in.web;

import com.easysubway.admin.web.AdminFormErrorView;
import com.easysubway.common.web.pagination.AdminPageRequest;
import com.easysubway.common.web.pagination.EgovPaginationView;
import com.easysubway.transit.application.port.in.TransitMasterAdminUseCase;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityStatusCommand;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.MasterDataWriteNotAllowedException;
import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class TransitFacilityAdminPageController {

	private final TransitFacilityStatusAssembler facilityStatusAssembler;
	private final TransitMasterAdminUseCase transitMasterAdminUseCase;

	TransitFacilityAdminPageController(
		TransitFacilityStatusAssembler facilityStatusAssembler,
		TransitMasterAdminUseCase transitMasterAdminUseCase
	) {
		this.facilityStatusAssembler = facilityStatusAssembler;
		this.transitMasterAdminUseCase = transitMasterAdminUseCase;
	}

	@GetMapping("/admin/facilities/page")
	String facilitiesPage(
		@RequestParam(required = false) String query,
		@RequestParam(required = false) String type,
		@RequestParam(required = false) AccessibilityFacilityStatus status,
		@RequestParam(required = false) String stationId,
		@RequestParam(required = false) String sort,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		populateFacilityStatusList(query, type, status, stationId, sort, page, size, model);
		return "admin/facilities/list";
	}

	// 시설 상태판 표준 테이블(#1741): 검색·유형·상태·역 필터는 htmx 부분 갱신, no-JS는 form GET.
	@HxRequest
	@GetMapping("/admin/facilities/page")
	String facilitiesListFragment(
		@RequestParam(required = false) String query,
		@RequestParam(required = false) String type,
		@RequestParam(required = false) AccessibilityFacilityStatus status,
		@RequestParam(required = false) String stationId,
		@RequestParam(required = false) String sort,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		@RequestHeader(value = "HX-History-Restore-Request", required = false) boolean historyRestore,
		Model model
	) {
		if (historyRestore) {
			return facilitiesPage(query, type, status, stationId, sort, page, size, model);
		}
		populateFacilityStatusList(query, type, status, stationId, sort, page, size, model);
		return "admin/facilities/list :: facilityResults";
	}

	private void populateFacilityStatusList(
		String query,
		String type,
		AccessibilityFacilityStatus status,
		String stationId,
		String sort,
		Integer page,
		Integer size,
		Model model
	) {
		AdminPageRequest pageRequest = AdminPageRequest.of(page, size);
		List<FacilityStatusRow> all = facilityStatusAssembler.assemble();
		// 필터 옵션(유형·역)은 필터 전 전체에서 뽑아 항상 전체 후보를 보여준다.
		model.addAttribute("typeOptions", distinctTypeOptions(all, type));
		model.addAttribute("stationFilterOptions", distinctStationOptions(all, stationId));

		String keyword = blankToNull(query);
		String typeLabel = blankToNull(type);
		String activeStation = blankToNull(stationId);
		String lowerKeyword = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);
		List<FacilityStatusRow> facilities = all.stream()
			.filter(row -> lowerKeyword == null
				|| row.facilityName().toLowerCase(Locale.ROOT).contains(lowerKeyword)
				|| row.stationName().toLowerCase(Locale.ROOT).contains(lowerKeyword))
			.filter(row -> typeLabel == null || typeLabel.equals(row.typeLabel()))
			.filter(row -> status == null || row.status() == status)
			.filter(row -> activeStation == null || activeStation.equals(row.stationId()))
			.sorted(facilityOrder(sort))
			.toList();

		EgovPaginationView pageView = EgovPaginationView.from(pageRequest.page(), pageRequest.size(), facilities.size());
		model.addAttribute("facilities", pageView.pageItems(facilities));
		model.addAttribute("page", pageView);
		Map<String, Object> linkParams = new LinkedHashMap<>();
		linkParams.put("query", keyword);
		linkParams.put("type", typeLabel);
		linkParams.put("status", status);
		linkParams.put("stationId", activeStation);
		linkParams.put("sort", blankToNull(sort));
		model.addAttribute("paginationLinks", pageView.links("/admin/facilities/page", linkParams));
		model.addAttribute("statusOptions", statusOptions());
		model.addAttribute("searchKeyword", keyword);
		model.addAttribute("selectedType", typeLabel);
		model.addAttribute("selectedStatus", status);
		model.addAttribute("selectedStationFilter", activeStation);
		model.addAttribute("selectedSort", blankToNull(sort));
		model.addAttribute("masterDataWritable", transitMasterAdminUseCase.masterDataCapability().writable());
	}

	private static List<FilterOption> distinctTypeOptions(List<FacilityStatusRow> rows, String selected) {
		List<FilterOption> options = new ArrayList<>();
		options.add(new FilterOption("", "전체 유형", blankToNull(selected) == null));
		rows.stream()
			.map(FacilityStatusRow::typeLabel)
			.distinct()
			.sorted()
			.forEach(label -> options.add(new FilterOption(label, label, label.equals(selected))));
		return options;
	}

	private static List<FilterOption> distinctStationOptions(List<FacilityStatusRow> rows, String selected) {
		List<FilterOption> options = new ArrayList<>();
		options.add(new FilterOption("", "전체 역", blankToNull(selected) == null));
		rows.stream()
			.collect(java.util.stream.Collectors.toMap(
				FacilityStatusRow::stationId, FacilityStatusRow::stationName, (a, b) -> a, LinkedHashMap::new))
			.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByValue())
			.forEach(entry -> options.add(
				new FilterOption(entry.getKey(), entry.getValue(), entry.getKey().equals(selected))));
		return options;
	}

	// 정렬: 상태(확인 필요 우선)·역명·갱신일. 기본은 역명 → 시설명.
	private static Comparator<FacilityStatusRow> facilityOrder(String sort) {
		String value = sort == null ? "" : sort;
		return switch (value) {
			case "attention" -> Comparator.comparingInt((FacilityStatusRow row) -> attentionRank(row.status()))
				.thenComparing(FacilityStatusRow::stationName)
				.thenComparing(FacilityStatusRow::facilityName);
			case "updated" -> Comparator.comparing(FacilityStatusRow::lastUpdatedAt).reversed()
				.thenComparing(FacilityStatusRow::stationName);
			default -> Comparator.comparing(FacilityStatusRow::stationName)
				.thenComparing(FacilityStatusRow::facilityName);
		};
	}

	// 확인이 필요한 상태(고장·공사·폐쇄·확인 필요·사용자 제보)를 앞으로 정렬하기 위한 순위. 판정은 도메인이 소유.
	private static int attentionRank(AccessibilityFacilityStatus status) {
		return status.needsAttention() ? 0 : 1;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	record FilterOption(String value, String label, boolean selected) {
	}

	@PostMapping("/admin/facilities/{facilityId}/page/status")
	@PreAuthorize("hasAuthority('admin.master.edit')")
	String updateFacilityStatusFromPage(
		@PathVariable String facilityId,
		@Valid @ModelAttribute("facilityStatusForm") FacilityStatusForm form,
		BindingResult bindingResult,
		Principal principal,
		RedirectAttributes redirectAttributes,
		Model model,
		HttpServletResponse response
	) {
		if (bindingResult.hasErrors()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			facilitiesPage(null, null, null, null, null, null, null, model);
			AdminFormErrorView.expose(model, bindingResult);
			return "admin/facilities/list";
		}
		try {
			transitMasterAdminUseCase.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
				facilityId,
				form.status(),
				principal.getName()
			));
		} catch (MasterDataWriteNotAllowedException exception) {
			redirectAttributes.addFlashAttribute("masterDataError", exception.getMessage());
		}
		return "redirect:/admin/facilities/page";
	}

	private static List<FacilityStatusOption> statusOptions() {
		return Arrays.stream(AccessibilityFacilityStatus.values())
			.map(status -> new FacilityStatusOption(status, FacilityStatusRow.statusLabel(status)))
			.toList();
	}

	record FacilityStatusOption(AccessibilityFacilityStatus value, String label) {
	}

	record FacilityStatusForm(
		@NotNull(message = "{validation.transit.facility-status.required}")
		AccessibilityFacilityStatus status
	) {
	}
}
