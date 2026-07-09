package com.easysubway.operator.adapter.in.web;

import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.StationWithLines;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class OperatorRepeatedBrokenFacilitiesAssembler {

	private final FacilityReportUseCase facilityReportUseCase;
	private final TransitMasterQueryUseCase transitMasterQueryUseCase;

	OperatorRepeatedBrokenFacilitiesAssembler(
		FacilityReportUseCase facilityReportUseCase,
		TransitMasterQueryUseCase transitMasterQueryUseCase
	) {
		this.facilityReportUseCase = facilityReportUseCase;
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
	}

	OperatorRepeatedBrokenFacilitiesView assemble() {
		return assemble(OperatorReportQuery.empty());
	}

	OperatorRepeatedBrokenFacilitiesView assemble(OperatorReportQuery query) {
		List<OperatorRepeatedBrokenFacilitiesView.RepeatedBrokenFacilityRow> rows = facilityReportUseCase
			.listRepeatedBrokenReportFacilities()
			.stream()
			.map(this::row)
			.flatMap(Optional::stream)
			.filter(row -> query.matches(row.stationName(), row.facilityName(), row.statusLabel()))
			.sorted(repeatedBrokenComparator(query))
			.toList();
		return new OperatorRepeatedBrokenFacilitiesView(rows.size(), rows);
	}

	private static Comparator<OperatorRepeatedBrokenFacilitiesView.RepeatedBrokenFacilityRow> repeatedBrokenComparator(
		OperatorReportQuery query
	) {
		Comparator<OperatorRepeatedBrokenFacilitiesView.RepeatedBrokenFacilityRow> comparator = switch (query.sort()) {
			case "station" -> Comparator.comparing(OperatorRepeatedBrokenFacilitiesView.RepeatedBrokenFacilityRow::stationName);
			case "facility" -> Comparator.comparing(OperatorRepeatedBrokenFacilitiesView.RepeatedBrokenFacilityRow::facilityName);
			case "status" -> Comparator.comparing(OperatorRepeatedBrokenFacilitiesView.RepeatedBrokenFacilityRow::statusLabel);
			default -> Comparator.comparingLong(OperatorRepeatedBrokenFacilitiesView.RepeatedBrokenFacilityRow::reportCount);
		};
		return query.sort().isBlank() || "desc".equals(query.direction()) ? comparator.reversed() : comparator;
	}

	private Optional<OperatorRepeatedBrokenFacilitiesView.RepeatedBrokenFacilityRow> row(
		RepeatedBrokenFacilityReportSummary summary
	) {
		try {
			StationWithLines station = transitMasterQueryUseCase.getStation(summary.stationId());
			return transitMasterQueryUseCase.listStationFacilities(summary.stationId())
				.stream()
				.filter(candidate -> candidate.id().equals(summary.facilityId()))
				.findFirst()
				.map(facility -> new OperatorRepeatedBrokenFacilitiesView.RepeatedBrokenFacilityRow(
					station.station().nameKo(),
					facility.name(),
					statusLabel(facility.status()),
					summary.reportCount()
				));
		} catch (StationNotFoundException exception) {
			return Optional.empty();
		}
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
}
