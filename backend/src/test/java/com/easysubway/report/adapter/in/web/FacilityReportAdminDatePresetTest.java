package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.common.web.WebMessageResolver;
import com.easysubway.report.adapter.in.web.FacilityReportAdminPageController.DatePresetLink;
import com.easysubway.report.adapter.in.web.FacilityReportAdminPageController.FacilityReportListPageRow;
import com.easysubway.report.adapter.out.persistence.InMemoryFacilityReportRepository;
import com.easysubway.report.application.service.FacilityReportService;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.transit.adapter.out.persistence.UnavailableTransitMasterRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 접수 시각은 UTC 벽시계로 저장되고 운영자의 "오늘"은 한국 달력 날짜다. UTC로는 전날인 한국 00~09시에
 * 접수된 신고가 "오늘" 프리셋에 들어가는지, 한국 자정 경계가 정확한지 본다.
 */
@DisplayName("관리자 시설 신고 접수일 프리셋의 한국 날짜 경계")
class FacilityReportAdminDatePresetTest {

	/** 한국 2026-10-18 01:30. UTC 날짜로는 아직 2026-10-17이다. */
	private static final Clock KOREAN_EARLY_MORNING = Clock.fixed(
		java.time.Instant.parse("2026-10-17T16:30:00Z"), ZoneOffset.UTC);

	private InMemoryFacilityReportRepository reportRepository;
	private FacilityReportAdminPageController controller;

	@BeforeEach
	void setUp() {
		var transitRepository = new UnavailableTransitMasterRepository();
		reportRepository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			transitRepository, transitRepository, reportRepository, reportRepository, KOREAN_EARLY_MORNING);
		controller = new FacilityReportAdminPageController(
			service, objectKey -> java.util.Optional.empty(), WebMessageResolver.defaultMessages(), KOREAN_EARLY_MORNING);
	}

	@Test
	@DisplayName("오늘·최근 7일·최근 30일 프리셋은 한국 날짜 기준이다")
	void presetsAreAnchoredOnTheKoreanDate() {
		List<DatePresetLink> presets = presetsFor(null, null);

		assertThat(presets).extracting(DatePresetLink::label)
			.containsExactly("오늘", "최근 7일", "최근 30일", "전체 기간");
		assertThat(rangeOf(presets.get(0))).containsExactly(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 18));
		assertThat(rangeOf(presets.get(1))).containsExactly(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18));
		assertThat(rangeOf(presets.get(2))).containsExactly(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 10, 18));
	}

	@Test
	@DisplayName("한국 날짜의 오늘 범위로 조회하면 UTC로 전날인 한국 새벽 접수 건이 포함된다")
	void todayPresetIncludesReportsReceivedInKoreanEarlyMorning() {
		save("kst-yesterday-last-second", LocalDateTime.of(2026, 10, 17, 14, 59, 59));
		save("kst-midnight", LocalDateTime.of(2026, 10, 17, 15, 0));
		save("kst-early-morning", LocalDateTime.of(2026, 10, 17, 16, 10));
		save("kst-tomorrow-midnight", LocalDateTime.of(2026, 10, 18, 15, 0));

		List<LocalDate> today = rangeOf(presetsFor(null, null).getFirst());

		assertThat(reportIdsReceivedBetween(today.get(0), today.get(1)))
			.containsExactlyInAnyOrder("kst-midnight", "kst-early-morning");
	}

	@Test
	@DisplayName("선택한 기간이 오늘 프리셋과 같으면 오늘 프리셋이 활성이다")
	void todayPresetIsActiveWhenTheSelectedRangeIsTheKoreanToday() {
		List<DatePresetLink> presets = presetsFor(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 18));

		assertThat(presets).extracting(DatePresetLink::active).containsExactly(true, false, false, false);
	}

	private List<DatePresetLink> presetsFor(LocalDate from, LocalDate to) {
		var model = new ExtendedModelMap();
		controller.reportListPage(null, null, null, null, null, from, to, null, null, null, null, model);
		@SuppressWarnings("unchecked")
		var presets = (List<DatePresetLink>) model.get("datePresets");
		return presets;
	}

	private List<String> reportIdsReceivedBetween(LocalDate from, LocalDate to) {
		var model = new ExtendedModelMap();
		controller.reportListPage(null, null, null, null, null, from, to, null, null, null, null, model);
		@SuppressWarnings("unchecked")
		var rows = (List<FacilityReportListPageRow>) model.get("reports");
		return rows.stream().map(FacilityReportListPageRow::id).toList();
	}

	private static List<LocalDate> rangeOf(DatePresetLink preset) {
		var params = UriComponentsBuilder.fromUriString(preset.href()).build().getQueryParams();
		return List.of(LocalDate.parse(params.getFirst("from")), LocalDate.parse(params.getFirst("to")));
	}

	private void save(String reportId, LocalDateTime createdAtUtc) {
		reportRepository.saveReport(new FacilityReport(
			reportId, "anonymous-user-1", "station-sangnoksu", "facility-elevator-1",
			FacilityReportType.BROKEN, "신고 내용", null, null, null, null, null, null,
			FacilityReportStatus.SUBMITTED, createdAtUtc, null, null));
	}
}
