package com.easysubway.devseed;

import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort.StoreFacilityReportPhotoCommand;
import com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort.StoredFacilityReportPhoto;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataSourceType;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * dev 프로파일 실사용 인상 평가·검수 스크린샷을 위한 opt-in 합성 데이터 seeder(#2327 PR⑤).
 *
 * <p>dev/H2 부팅 시 pilot 고정 fixture({@link com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository})
 * 외에는 지표·신고·시설 확인 대기열이 전부 비어 있어 대시보드 추이 차트·카드 스파크라인·전일 대비·신고
 * 대기열이 무엇을 채우면 채워지는지 눈으로 검증할 수 없었다. 이 러너는 그 간극만 메운다.
 *
 * <p>이중 가드: {@link Profile}로 prod 계열 프로파일에서는 빈 자체가 등록되지 않고,
 * {@link ConditionalOnProperty}로 {@code easysubway.dev-seed.enabled}(env {@code EASYSUBWAY_DEV_SEED})가
 * {@code true}일 때만 등록된다(패턴은 {@link com.easysubway.route.adapter.out.persistence.TimetableSeedLoader}와
 * 동일). CI는 이 플래그를 켜지 않으므로 기존 seed 전제·QA 하네스 계약에 영향이 없다.
 *
 * <p>seed 범위: (1) 관리자 대시보드가 소비하는 6개 지표 키에 최근 30일 합성 추이, (2) 상태가 다양한
 * 시설 신고(사진 포함 2건), (3) InMemory 저장소가 쓰기를 지원하는 접근성 시설 소량. 역·출구는 이
 * 저장소가 조회 전용 정적 목록이라 저장 포트가 없어 seed하지 않는다(우회하지 않음).
 */
@Component
@Profile("!prod & !staging & !release & !prod-like")
@ConditionalOnProperty(name = "easysubway.dev-seed.enabled", havingValue = "true")
public class DevFixtureSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DevFixtureSeeder.class);

	// FacilityReportControllerTest의 VALID_PNG_BASE64(golden fixture)와 동일한 1×1 PNG.
	private static final String SEED_PHOTO_PNG_BASE64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";

	private static final int METRIC_TREND_DAYS = 30;
	private static final String SEED_STATION_ID = "station-sadang";
	private static final String SEED_EXIT_ID = "exit-sadang-2";

	private final LoadTransitMasterPort loadTransitMasterPort;
	private final SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort;
	private final SaveFacilityReportPort saveFacilityReportPort;
	private final StoreFacilityReportPhotoPort storeFacilityReportPhotoPort;
	private final AdminMetricDailyRepository adminMetricDailyRepository;
	private final Clock clock;

	@Autowired
	public DevFixtureSeeder(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		SaveFacilityReportPort saveFacilityReportPort,
		StoreFacilityReportPhotoPort storeFacilityReportPhotoPort,
		AdminMetricDailyRepository adminMetricDailyRepository,
		ObjectProvider<Clock> clockProvider
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			saveFacilityReportPort,
			storeFacilityReportPhotoPort,
			adminMetricDailyRepository,
			clockProvider.getIfAvailable(Clock::systemDefaultZone)
		);
	}

	DevFixtureSeeder(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		SaveFacilityReportPort saveFacilityReportPort,
		StoreFacilityReportPhotoPort storeFacilityReportPhotoPort,
		AdminMetricDailyRepository adminMetricDailyRepository,
		Clock clock
	) {
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.saveAccessibilityFacilityStatusPort = saveAccessibilityFacilityStatusPort;
		this.saveFacilityReportPort = saveFacilityReportPort;
		this.storeFacilityReportPhotoPort = storeFacilityReportPhotoPort;
		this.adminMetricDailyRepository = adminMetricDailyRepository;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		seedAccessibilityFacilities();
		int reportCount = seedFacilityReports();
		int metricRowCount = seedAdminMetrics();
		log.info(
			"dev seed applied: {} facility reports, {} admin metric snapshots ({} days x {} keys)",
			reportCount, metricRowCount, METRIC_TREND_DAYS, SEED_METRIC_KEYS.size()
		);
	}

	// station-sadang은 fixture상 실존 역이지만 접근성 시설이 하나도 없어 시설 목록·신고 대기열
	// 데모가 station-sangnoksu 한 역에 몰린다. InMemoryTransitMasterRepository가 지원하는
	// SaveAccessibilityFacilityStatusPort#saveAccessibilityFacility로 소량만 보탠다(#2327).
	private void seedAccessibilityFacilities() {
		saveAccessibilityFacilityStatusPort.saveAccessibilityFacility(new AccessibilityFacility(
			"facility-sadang-elevator-1",
			SEED_STATION_ID,
			SEED_EXIT_ID,
			AccessibilityFacilityType.ELEVATOR,
			"2번 출구 엘리베이터",
			"지상",
			"대합실",
			null,
			null,
			"2번 출구와 대합실을 연결합니다.",
			AccessibilityFacilityStatus.BROKEN,
			DataConfidenceLevel.MEDIUM,
			DataSourceType.USER_REPORT,
			LocalDate.now(clock).minusDays(3)
		));
		saveAccessibilityFacilityStatusPort.saveAccessibilityFacility(new AccessibilityFacility(
			"facility-sadang-accessible-toilet",
			SEED_STATION_ID,
			null,
			AccessibilityFacilityType.ACCESSIBLE_TOILET,
			"장애인 화장실",
			"대합실",
			"대합실",
			null,
			null,
			"개찰구 안쪽 대합실에 있습니다.",
			AccessibilityFacilityStatus.UNKNOWN,
			DataConfidenceLevel.NEEDS_VERIFICATION,
			DataSourceType.USER_REPORT,
			LocalDate.now(clock).minusDays(1)
		));
	}

	private int seedFacilityReports() {
		List<AccessibilityFacility> facilities = loadTransitMasterPort.loadAccessibilityFacilities();
		if (facilities.isEmpty()) {
			log.warn("dev seed skipped facility reports: no accessibility facilities available to reference");
			return 0;
		}

		LocalDateTime now = LocalDateTime.now(clock);
		SeedPhoto seedPhoto = storeSeedPhoto("dev-seed-report-1");
		SeedPhoto resolvedPhoto = storeSeedPhoto("dev-seed-report-6");

		List<SeedReportSpec> specs = List.of(
			new SeedReportSpec("dev-seed-report-1", facilityById(facilities, "facility-sangnoksu-elevator-1"),
				FacilityReportType.BROKEN,
				"엘리베이터가 작동하지 않습니다.", FacilityReportStatus.SUBMITTED,
				now.minusHours(2), null, seedPhoto, null),
			new SeedReportSpec("dev-seed-report-2", facilityById(facilities, "facility-sangnoksu-escalator-1"),
				FacilityReportType.STAIRS_PRESENT,
				"에스컬레이터 앞에 안내 없이 계단만 있습니다.", FacilityReportStatus.UNDER_REVIEW,
				now.minusHours(30), null, null, null),
			new SeedReportSpec("dev-seed-report-3", facilityById(facilities, "facility-sangnoksu-accessible-toilet"),
				FacilityReportType.INFORMATION_WRONG,
				"화장실 위치 안내가 실제 위치와 다릅니다.", FacilityReportStatus.SUBMITTED,
				now.minusHours(80), null, null, null),
			new SeedReportSpec("dev-seed-report-4", facilityById(facilities, "facility-sangnoksu-elevator-1"),
				FacilityReportType.ROUTE_BLOCKED,
				"휠체어 이동 경로에 적재물이 쌓여 있습니다.", FacilityReportStatus.ACCEPTED,
				now.minusDays(5), now.minusDays(4), null, null),
			new SeedReportSpec("dev-seed-report-5", facilityById(facilities, "facility-sadang-elevator-1"),
				FacilityReportType.CLOSED,
				"공사로 폐쇄됐다는 제보였으나 확인 결과 정상 운영 중이었습니다.", FacilityReportStatus.REJECTED,
				now.minusDays(6), now.minusDays(5), null, null),
			new SeedReportSpec("dev-seed-report-6", facilityById(facilities, "facility-sadang-accessible-toilet"),
				FacilityReportType.RECOVERED,
				"신고 후 시설 점검이 완료되어 정상화됐습니다.", FacilityReportStatus.RESOLVED,
				now.minusDays(10), now.minusDays(9), resolvedPhoto, null),
			// dev-seed-report-2와 동일 시설·동일 사안의 중복 제보. DUPLICATE 상태는 실제 도메인에서
			// FacilityReportService#resolveDuplicateOfReportId가 실존 기준 신고 참조를 강제하므로
			// seed도 같은 불변식을 지켜 기준 신고 id를 채운다.
			new SeedReportSpec("dev-seed-report-7", facilityById(facilities, "facility-sangnoksu-escalator-1"),
				FacilityReportType.STAIRS_PRESENT,
				"에스컬레이터 앞 계단 안내 부족 제보가 중복 접수되었습니다. 기존 신고와 동일 사안입니다.",
				FacilityReportStatus.DUPLICATE,
				now.minusHours(20), now.minusHours(19), null, "dev-seed-report-2")
		);

		int saved = 0;
		for (SeedReportSpec spec : specs) {
			if (spec.facility() == null) {
				continue;
			}
			saveFacilityReportPort.saveReport(spec.toFacilityReport());
			saved++;
		}
		return saved;
	}

	private SeedPhoto storeSeedPhoto(String reportId) {
		byte[] photoBytes = Base64.getDecoder().decode(SEED_PHOTO_PNG_BASE64);
		String sha256 = sha256Hex(photoBytes);
		StoredFacilityReportPhoto stored = storeFacilityReportPhotoPort.storeFacilityReportPhoto(
			new StoreFacilityReportPhotoCommand(
				reportId, "seed-photo.png", "image/png", photoBytes, photoBytes, sha256, photoBytes.length));
		return new SeedPhoto(stored, sha256, (long) photoBytes.length);
	}

	// 위치 인덱스 대신 fixture id로 조회한다. InMemoryTransitMasterRepository의 fixture가 추가·재정렬돼도
	// 신고 유형-설명 정합이 조용히 어긋나지 않고, 참조 id가 존재하지 않으면 명시적으로 로그를 남기고
	// 해당 신고만 건너뛴다(#2327 리뷰 지적).
	private static AccessibilityFacility facilityById(List<AccessibilityFacility> facilities, String facilityId) {
		return facilities.stream()
			.filter(candidate -> facilityId.equals(candidate.id()))
			.findFirst()
			.orElseGet(() -> {
				log.warn("dev seed skipped report referencing missing facility id={}", facilityId);
				return null;
			});
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	// 대시보드 추이 차트 2개(제보 추이, 경로 차단률·API 오류율 추이)와 핵심 카드 4개가 함께 참조하는
	// 지표 키의 합집합. AdminOverviewPageController#populateTrends/dashboardPage와 정확히 같은 키를
	// 채워야 seed on/off 비교에서 화면 전 영역이 함께 채워진다(#2327).
	private static final List<String> SEED_METRIC_KEYS = List.of(
		AdminMetricKeys.REPORTS_RECENT_24H,
		AdminMetricKeys.REPORTS_PENDING,
		AdminMetricKeys.ROUTE_BLOCKED_RATE,
		AdminMetricKeys.API_ERROR_RATE,
		AdminMetricKeys.FACILITIES_NEEDS_VERIFICATION,
		AdminMetricKeys.PUSH_FAILED
	);

	private int seedAdminMetrics() {
		LocalDate today = LocalDate.now(clock);
		LocalDate from = today.minusDays(METRIC_TREND_DAYS - 1L);
		int saved = 0;
		for (String metricKey : SEED_METRIC_KEYS) {
			MetricWave wave = waveFor(metricKey);
			int dayIndex = 0;
			for (LocalDate date = from; !date.isAfter(today); date = date.plusDays(1)) {
				adminMetricDailyRepository.save(AdminMetricDaily.scalar(metricKey, date, wave.valueAt(dayIndex)));
				dayIndex++;
				saved++;
			}
		}
		return saved;
	}

	// (지표 키별) 기준값 + 완만한 정현파 + 소량의 결정적 jitter로 "그럴듯한 자연스러운 변동"을 만든다.
	// 난수 시드 없이도 매 부팅 동일한 형태를 재현해 스크린샷·회귀 비교가 가능하다.
	private static MetricWave waveFor(String metricKey) {
		return switch (metricKey) {
			case AdminMetricKeys.REPORTS_RECENT_24H -> new MetricWave(5, 2.5, 0.6, 0.0, true);
			case AdminMetricKeys.REPORTS_PENDING -> new MetricWave(7, 3.0, 0.7, 0.8, true);
			case AdminMetricKeys.ROUTE_BLOCKED_RATE -> new MetricWave(3.2, 1.5, 0.3, 1.6, false);
			case AdminMetricKeys.API_ERROR_RATE -> new MetricWave(0.9, 0.5, 0.1, 2.4, false);
			case AdminMetricKeys.FACILITIES_NEEDS_VERIFICATION -> new MetricWave(2, 1.0, 0.4, 3.1, true);
			case AdminMetricKeys.PUSH_FAILED -> new MetricWave(4, 2.0, 0.5, 4.2, true);
			default -> new MetricWave(1, 0.5, 0.2, 0.0, true);
		};
	}

	private record MetricWave(double base, double amplitude, double jitterScale, double phase, boolean roundToInt) {

		double valueAt(int dayIndex) {
			double seasonal = amplitude * Math.sin(dayIndex * 0.35 + phase);
			double jitter = ((dayIndex * 47 + 13) % 7 - 3) * jitterScale;
			double value = Math.max(0.0, base + seasonal + jitter);
			return roundToInt ? Math.round(value) : Math.round(value * 10.0) / 10.0;
		}
	}

	private record SeedPhoto(StoredFacilityReportPhoto stored, String sha256, long sizeBytes) {
	}

	private record SeedReportSpec(
		String id,
		AccessibilityFacility facility,
		FacilityReportType reportType,
		String description,
		FacilityReportStatus status,
		LocalDateTime createdAt,
		LocalDateTime reviewedAt,
		SeedPhoto photo,
		String duplicateOfReportId
	) {

		FacilityReport toFacilityReport() {
			boolean hasPhoto = photo != null;
			return new FacilityReport(
				id,
				"dev-seed-user",
				facility.stationId(),
				facility.id(),
				reportType,
				description,
				hasPhoto ? "seed-photo.png" : null,
				hasPhoto ? "image/png" : null,
				hasPhoto ? photo.stored().objectKey() : null,
				hasPhoto ? photo.stored().thumbnailObjectKey() : null,
				hasPhoto ? photo.sha256() : null,
				hasPhoto ? photo.sizeBytes() : null,
				facility.latitude(),
				facility.longitude(),
				duplicateOfReportId,
				status,
				createdAt,
				reviewedAt,
				reviewedAt == null ? null : "dev-seed-operator"
			);
		}
	}
}
