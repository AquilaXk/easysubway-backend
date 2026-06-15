package com.easysubway.transit.adapter.in.web;

import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.TransitMasterAdminUseCase;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityStatusCommand;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.StationWithLines;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class TransitFacilityAdminPageController {

	private final TransitMasterQueryUseCase transitMasterQueryUseCase;
	private final TransitMasterAdminUseCase transitMasterAdminUseCase;

	TransitFacilityAdminPageController(
		TransitMasterQueryUseCase transitMasterQueryUseCase,
		TransitMasterAdminUseCase transitMasterAdminUseCase
	) {
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
		this.transitMasterAdminUseCase = transitMasterAdminUseCase;
	}

	@GetMapping("/admin/facilities/page")
	String facilitiesPage(Model model) {
		model.addAttribute("facilities", facilityRows());
		model.addAttribute("statusOptions", statusOptions());
		return "admin/facilities/list";
	}

	@PostMapping("/admin/facilities/{facilityId}/page/status")
	String updateFacilityStatusFromPage(
		@PathVariable String facilityId,
		@RequestParam AccessibilityFacilityStatus status,
		Principal principal
	) {
		transitMasterAdminUseCase.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			facilityId,
			status,
			principal.getName()
		));
		return "redirect:/admin/facilities/page";
	}

	private List<FacilityStatusRow> facilityRows() {
		// 관리자 화면은 역별 시설 상태를 한눈에 보도록 역 목록을 먼저 펼친 뒤 시설 행으로 변환한다.
		return transitMasterQueryUseCase.searchStations(new StationSearchCommand(null, null))
			.stream()
			.flatMap(station -> transitMasterQueryUseCase.listStationFacilities(station.station().id())
				.stream()
				.map(facility -> FacilityStatusRow.from(station, facility)))
			.toList();
	}

	private static List<FacilityStatusOption> statusOptions() {
		return Arrays.stream(AccessibilityFacilityStatus.values())
			.map(status -> new FacilityStatusOption(status, statusLabel(status)))
			.toList();
	}

	private static String typeLabel(AccessibilityFacilityType type) {
		return switch (type) {
			case ELEVATOR -> "엘리베이터";
			case ESCALATOR -> "에스컬레이터";
			case WHEELCHAIR_LIFT -> "휠체어 리프트";
			case RAMP -> "경사로";
			case ACCESSIBLE_TOILET -> "장애인 화장실";
			case TOILET -> "화장실";
			case NURSING_ROOM -> "수유실";
			case CUSTOMER_CENTER -> "고객센터";
		};
	}

	private static String statusLabel(AccessibilityFacilityStatus status) {
		return switch (status) {
			case NORMAL -> "정상";
			case BROKEN -> "고장";
			case UNDER_CONSTRUCTION -> "공사 중";
			case CLOSED -> "폐쇄";
			case UNKNOWN -> "확인 필요";
			case USER_REPORTED -> "사용자 제보";
			case ADMIN_VERIFIED -> "관리자 확인";
		};
	}

	private static String confidenceLabel(DataConfidenceLevel confidence) {
		return switch (confidence) {
			case HIGH -> "정보 신뢰도 높음";
			case MEDIUM -> "정보 신뢰도 보통";
			case LOW -> "정보 신뢰도 낮음";
			case NEEDS_VERIFICATION -> "정보 확인 필요";
		};
	}

	record FacilityStatusRow(
		String facilityId,
		String stationId,
		String stationName,
		String facilityName,
		String typeLabel,
		AccessibilityFacilityStatus status,
		String statusLabel,
		String confidenceLabel,
		LocalDate lastUpdatedAt
	) {

		static FacilityStatusRow from(StationWithLines station, AccessibilityFacility facility) {
			return new FacilityStatusRow(
				facility.id(),
				station.station().id(),
				station.station().nameKo(),
				facility.name(),
				TransitFacilityAdminPageController.typeLabel(facility.type()),
				facility.status(),
				TransitFacilityAdminPageController.statusLabel(facility.status()),
				TransitFacilityAdminPageController.confidenceLabel(facility.dataConfidence()),
				facility.lastUpdatedAt()
			);
		}
	}

	record FacilityStatusOption(AccessibilityFacilityStatus value, String label) {
	}
}
