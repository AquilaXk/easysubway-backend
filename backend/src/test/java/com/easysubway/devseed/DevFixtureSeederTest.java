package com.easysubway.devseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.admin.metric.adapter.out.persistence.InMemoryAdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import com.easysubway.report.adapter.out.persistence.InMemoryFacilityReportRepository;
import com.easysubway.report.adapter.out.storage.LocalFacilityReportPhotoStorage;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * dev seed 실동작 검증(#2327 PR⑤). 실제 InMemory 어댑터(운영 dev 프로파일과 동일한 구현체)를 그대로
 * 붙여 (1) 접근성 시설이 늘고, (2) 상태가 다양한 신고가 쌓이고, (3) 대시보드가 읽는 6개 지표 키에
 * 30일치 스냅샷이 upsert되는지 확인한다. Spring 컨텍스트 없이 러너를 직접 호출한다(가드는
 * {@link DevFixtureSeederConditionTest}가 별도로 검증).
 */
class DevFixtureSeederTest {

	private static final Clock FIXED_CLOCK =
		Clock.fixed(Instant.parse("2026-07-20T09:00:00Z"), ZoneOffset.UTC);

	@TempDir
	Path photoStorageDir;

	@Test
	void seedsFacilitiesReportsAndMetricsWithoutTouchingCiSeedPresumptions() {
		InMemoryTransitMasterRepository transitMasterRepository = new InMemoryTransitMasterRepository();
		InMemoryFacilityReportRepository reportRepository = new InMemoryFacilityReportRepository();
		InMemoryAdminMetricDailyRepository metricRepository = new InMemoryAdminMetricDailyRepository();
		LocalFacilityReportPhotoStorage photoStorage = new LocalFacilityReportPhotoStorage(photoStorageDir);

		int factoryFacilityCount = transitMasterRepository.loadAccessibilityFacilities().size();
		int factoryReportCount = reportRepository.loadReports().size();

		DevFixtureSeeder seeder = new DevFixtureSeeder(
			transitMasterRepository,
			transitMasterRepository,
			reportRepository,
			photoStorage,
			metricRepository,
			FIXED_CLOCK
		);

		seeder.run(null);

		// (1) 접근성 시설: station-sadang에 소량 추가되어 station-sangnoksu 한 역에 몰려 있던 데모가 완화된다.
		assertThat(transitMasterRepository.loadAccessibilityFacilities())
			.hasSizeGreaterThan(factoryFacilityCount);
		assertThat(transitMasterRepository.loadAccessibilityFacilities())
			.extracting("stationId")
			.contains("station-sadang");

		// (2) 신고: 상태가 다양하고(대기·진행·수락·반려·해결·중복), 실존 facility/station id만 참조하며,
		// 사진이 있는 신고는 실제로 로드 가능한 오브젝트 키를 갖는다(CI golden fixture와 같은 1x1 PNG 재사용).
		List<FacilityReport> reports = reportRepository.loadReports();
		assertThat(reports.size()).isGreaterThan(factoryReportCount);
		Set<String> validFacilityIds = transitMasterRepository.loadAccessibilityFacilities().stream()
			.map(facility -> facility.id())
			.collect(Collectors.toSet());
		assertThat(reports).allSatisfy(report -> assertThat(validFacilityIds).contains(report.facilityId()));

		Set<FacilityReportStatus> seededStatuses = reports.stream()
			.map(FacilityReport::status)
			.collect(Collectors.toSet());
		assertThat(seededStatuses).contains(
			FacilityReportStatus.SUBMITTED,
			FacilityReportStatus.UNDER_REVIEW,
			FacilityReportStatus.ACCEPTED,
			FacilityReportStatus.REJECTED,
			FacilityReportStatus.RESOLVED,
			FacilityReportStatus.DUPLICATE);

		// DUPLICATE 상태는 실제 도메인에서 실존 기준 신고 참조를 강제하므로(FacilityReportService
		// #resolveDuplicateOfReportId), seed도 duplicateOfReportId가 채워진 채 실존 신고를 참조해야 한다.
		Set<String> reportIds = reports.stream().map(FacilityReport::id).collect(Collectors.toSet());
		List<FacilityReport> duplicateReports = reports.stream()
			.filter(report -> report.status() == FacilityReportStatus.DUPLICATE)
			.toList();
		assertThat(duplicateReports).isNotEmpty();
		assertThat(duplicateReports).allSatisfy(report -> {
			assertThat(report.duplicateOfReportId()).isNotBlank();
			assertThat(reportIds).contains(report.duplicateOfReportId());
		});

		List<FacilityReport> reportsWithPhoto = reports.stream().filter(FacilityReport::hasPhoto).toList();
		assertThat(reportsWithPhoto).isNotEmpty();
		for (FacilityReport report : reportsWithPhoto) {
			assertThat(photoStorage.loadFacilityReportPhoto(report.photoObjectKey())).isPresent();
		}

		// (3) 지표: 대시보드 추이 차트·카드가 읽는 6개 키 x 30일 = 180 스냅샷이 upsert된다.
		List<String> dashboardKeys = List.of(
			AdminMetricKeys.REPORTS_RECENT_24H,
			AdminMetricKeys.REPORTS_PENDING,
			AdminMetricKeys.ROUTE_BLOCKED_RATE,
			AdminMetricKeys.API_ERROR_RATE,
			AdminMetricKeys.FACILITIES_NEEDS_VERIFICATION,
			AdminMetricKeys.PUSH_FAILED
		);
		LocalDate today = LocalDate.now(FIXED_CLOCK);
		LocalDate from = today.minusDays(29);
		assertThat(metricRepository.findByKeysAndDateRange(dashboardKeys, from, today)).hasSize(180);
		assertThat(metricRepository.find(AdminMetricKeys.REPORTS_RECENT_24H, today)).isPresent();
		assertThat(metricRepository.find(AdminMetricKeys.REPORTS_RECENT_24H, from)).isPresent();
	}

	@Test
	void skipsReportSeedButStillSeedsMetricsWhenNoAccessibilityFacilitiesAreAvailable() {
		// LoadTransitMasterPort가 시설이 전혀 없는 조회 전용 저장소일 때도 예외 없이 안전하게 넘어가는지
		// (신고 seed만 건너뛰고 지표 seed는 계속 진행하는지) 확인한다.
		InMemoryAdminMetricDailyRepository metricRepository = new InMemoryAdminMetricDailyRepository();
		LoadTransitMasterPort emptyMasterPort = mock(LoadTransitMasterPort.class);
		when(emptyMasterPort.loadAccessibilityFacilities()).thenReturn(List.of());
		SaveAccessibilityFacilityStatusPort saveFacilityPort = mock(SaveAccessibilityFacilityStatusPort.class);
		SaveFacilityReportPort saveReportPort = mock(SaveFacilityReportPort.class);
		when(saveReportPort.saveReport(any())).thenAnswer(invocation -> invocation.getArgument(0));

		DevFixtureSeeder seeder = new DevFixtureSeeder(
			emptyMasterPort,
			saveFacilityPort,
			saveReportPort,
			new LocalFacilityReportPhotoStorage(photoStorageDir),
			metricRepository,
			FIXED_CLOCK
		);

		seeder.run(null);

		assertThat(metricRepository.find(AdminMetricKeys.REPORTS_RECENT_24H, LocalDate.now(FIXED_CLOCK))).isPresent();
	}
}
