package com.easysubway.report.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.report.adapter.out.persistence.InMemoryFacilityReportRepository;
import com.easysubway.report.application.port.in.CreateFacilityReportCommand;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.domain.FacilityReportNotFoundException;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportTargetNotFoundException;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.report.domain.InvalidFacilityReportException;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.StationNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("시설 신고 서비스")
class FacilityReportServiceTest {

	private final InMemoryFacilityReportRepository reportRepository = new InMemoryFacilityReportRepository();
	private final InMemoryTransitMasterRepository transitRepository = new InMemoryTransitMasterRepository();
	private final FacilityReportService service = new FacilityReportService(
		transitRepository,
		transitRepository,
		reportRepository,
		reportRepository,
		Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
	);

	@Test
	@DisplayName("시설 신고는 제출 상태로 저장되고 다시 조회된다")
	void createReportStoresSubmittedFacilityReport() {
		var report = service.createReport(new CreateFacilityReportCommand(
			"anonymous-user-1",
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"엘리베이터 문이 열리지 않습니다.",
			null,
			new BigDecimal("37.302421"),
			new BigDecimal("126.866221")
		));

		assertThat(report.id()).isNotBlank();
		assertThat(report.stationId()).isEqualTo("station-sangnoksu");
		assertThat(report.facilityId()).isEqualTo("facility-sangnoksu-elevator-1");
		assertThat(report.reportType()).isEqualTo(FacilityReportType.BROKEN);
		assertThat(report.status()).isEqualTo(FacilityReportStatus.SUBMITTED);
		assertThat(report.createdAt()).isEqualTo(LocalDateTime.of(2026, 6, 12, 9, 0));
		assertThat(service.getReport(report.id())).isEqualTo(report);
	}

	@Test
	@DisplayName("시설 신고는 존재하는 역을 요구한다")
	void createReportRequiresExistingStation() {
		assertThatThrownBy(() -> service.createReport(new CreateFacilityReportCommand(
			"anonymous-user-1",
			"missing-station",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"엘리베이터가 멈춰 있습니다.",
			null,
			null,
			null
		)))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("시설 신고는 해당 역에 속한 시설을 요구한다")
	void createReportRequiresFacilityInStation() {
		assertThatThrownBy(() -> service.createReport(new CreateFacilityReportCommand(
			"anonymous-user-1",
			"station-sadang",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"다른 역 시설로 신고할 수 없습니다.",
			null,
			null,
			null
		)))
			.isInstanceOf(FacilityReportTargetNotFoundException.class)
			.hasMessage("시설 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("시설 신고는 신고 유형을 요구한다")
	void createReportRequiresReportType() {
		assertThatThrownBy(() -> service.createReport(new CreateFacilityReportCommand(
			"anonymous-user-1",
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			null,
			"신고 유형이 없는 요청입니다.",
			null,
			null,
			null
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("신고 유형을 선택해야 합니다.");
	}

	@Test
	@DisplayName("존재하지 않는 신고는 조회할 수 없다")
	void getReportRequiresExistingReport() {
		assertThatThrownBy(() -> service.getReport("missing-report"))
			.isInstanceOf(FacilityReportNotFoundException.class)
			.hasMessage("신고 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("신고 목록은 최신 신고부터 반환한다")
	void listReportsReturnsNewestReportFirst() {
		FacilityReportService serviceWithTickingClock = serviceWithClock(new TickingClock());

		var first = serviceWithTickingClock.createReport(reportCommand("anonymous-user-list-1", "처음 접수한 신고"));
		var second = serviceWithTickingClock.createReport(reportCommand("anonymous-user-list-2", "나중에 접수한 신고"));

		assertThat(serviceWithTickingClock.listReports(null))
			.extracting("id")
			.containsExactly(second.id(), first.id());
	}

	@Test
	@DisplayName("신고 목록은 상태별로 필터링한다")
	void listReportsCanFilterByStatus() {
		FacilityReportService serviceWithTickingClock = serviceWithClock(new TickingClock());

		var submitted = serviceWithTickingClock.createReport(reportCommand("anonymous-user-submitted", "검수 대기 신고"));
		var accepted = serviceWithTickingClock.createReport(reportCommand("anonymous-user-accepted", "승인할 신고"));

		serviceWithTickingClock.reviewReport(new ReviewFacilityReportCommand(
			accepted.id(),
			FacilityReportReviewDecision.ACCEPT,
			"admin-1"
		));

		assertThat(serviceWithTickingClock.listReports(FacilityReportStatus.SUBMITTED))
			.extracting("id")
			.contains(submitted.id())
			.doesNotContain(accepted.id());
		assertThat(serviceWithTickingClock.listReports(FacilityReportStatus.ACCEPTED))
			.extracting("id")
			.contains(accepted.id())
			.doesNotContain(submitted.id());
	}

	@Test
	@DisplayName("신고 검수 승인 결과와 검수자를 저장한다")
	void reviewReportStoresAcceptedDecisionAndReviewer() {
		var report = service.createReport(new CreateFacilityReportCommand(
			"anonymous-user-1",
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"엘리베이터 문이 열리지 않습니다.",
			null,
			null,
			null
		));

		var reviewed = service.reviewReport(new ReviewFacilityReportCommand(
			report.id(),
			FacilityReportReviewDecision.ACCEPT,
			"admin-1"
		));

		assertThat(reviewed.status()).isEqualTo(FacilityReportStatus.ACCEPTED);
		assertThat(reviewed.reviewedAt()).isEqualTo(LocalDateTime.of(2026, 6, 12, 9, 0));
		assertThat(reviewed.reviewedBy()).isEqualTo("admin-1");
		assertThat(service.getReport(report.id())).isEqualTo(reviewed);
	}

	@Test
	@DisplayName("승인된 시설 신고는 신고 유형에 맞춰 시설 상태를 바꾼다")
	void acceptedReportUpdatesFacilityStatusByReportType() {
		var transitRepository = new InMemoryTransitMasterRepository();
		var serviceWithFacilityStatus = serviceWithTransitRepository(transitRepository);

		var broken = serviceWithFacilityStatus.createReport(reportCommand(
			"anonymous-user-broken",
			FacilityReportType.BROKEN,
			"엘리베이터가 멈춰 있습니다."
		));
		serviceWithFacilityStatus.reviewReport(reviewCommand(broken.id(), FacilityReportReviewDecision.ACCEPT));
		assertThat(facilityStatus(transitRepository, "facility-sangnoksu-elevator-1"))
			.isEqualTo(AccessibilityFacilityStatus.BROKEN);

		var underConstruction = serviceWithFacilityStatus.createReport(reportCommand(
			"anonymous-user-construction",
			FacilityReportType.UNDER_CONSTRUCTION,
			"시설 앞 공사가 진행 중입니다."
		));
		serviceWithFacilityStatus.reviewReport(reviewCommand(underConstruction.id(), FacilityReportReviewDecision.ACCEPT));
		assertThat(facilityStatus(transitRepository, "facility-sangnoksu-elevator-1"))
			.isEqualTo(AccessibilityFacilityStatus.UNDER_CONSTRUCTION);

		var closed = serviceWithFacilityStatus.createReport(reportCommand(
			"anonymous-user-closed",
			FacilityReportType.CLOSED,
			"출입이 막혀 있습니다."
		));
		serviceWithFacilityStatus.reviewReport(reviewCommand(closed.id(), FacilityReportReviewDecision.ACCEPT));
		assertThat(facilityStatus(transitRepository, "facility-sangnoksu-elevator-1"))
			.isEqualTo(AccessibilityFacilityStatus.CLOSED);

		var recovered = serviceWithFacilityStatus.createReport(reportCommand(
			"anonymous-user-recovered",
			FacilityReportType.RECOVERED,
			"다시 정상 이용 가능합니다."
		));
		serviceWithFacilityStatus.reviewReport(reviewCommand(recovered.id(), FacilityReportReviewDecision.ACCEPT));
		assertThat(facilityStatus(transitRepository, "facility-sangnoksu-elevator-1"))
			.isEqualTo(AccessibilityFacilityStatus.NORMAL);
	}

	@Test
	@DisplayName("반려와 중복 처리된 시설 신고는 시설 상태를 바꾸지 않는다")
	void rejectedOrDuplicateReportDoesNotUpdateFacilityStatus() {
		var transitRepository = new InMemoryTransitMasterRepository();
		var serviceWithFacilityStatus = serviceWithTransitRepository(transitRepository);

		var rejected = serviceWithFacilityStatus.createReport(reportCommand(
			"anonymous-user-rejected-status",
			FacilityReportType.BROKEN,
			"반려할 고장 신고입니다."
		));
		serviceWithFacilityStatus.reviewReport(reviewCommand(rejected.id(), FacilityReportReviewDecision.REJECT));
		assertThat(facilityStatus(transitRepository, "facility-sangnoksu-elevator-1"))
			.isEqualTo(AccessibilityFacilityStatus.NORMAL);

		var duplicated = serviceWithFacilityStatus.createReport(reportCommand(
			"anonymous-user-duplicate-status",
			FacilityReportType.CLOSED,
			"중복 처리할 폐쇄 신고입니다."
		));
		serviceWithFacilityStatus.reviewReport(reviewCommand(duplicated.id(), FacilityReportReviewDecision.MARK_DUPLICATE));
		assertThat(facilityStatus(transitRepository, "facility-sangnoksu-elevator-1"))
			.isEqualTo(AccessibilityFacilityStatus.NORMAL);
	}

	@Test
	@DisplayName("신고 검수는 반려와 중복 처리 상태를 저장한다")
	void reviewReportCanRejectOrMarkDuplicate() {
		var rejected = service.createReport(new CreateFacilityReportCommand(
			"anonymous-user-1",
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.INFORMATION_WRONG,
			"기존 정보가 맞습니다.",
			null,
			null,
			null
		));
		var duplicated = service.createReport(new CreateFacilityReportCommand(
			"anonymous-user-2",
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"이미 접수된 신고입니다.",
			null,
			null,
			null
		));

		assertThat(service.reviewReport(new ReviewFacilityReportCommand(
			rejected.id(),
			FacilityReportReviewDecision.REJECT,
			"admin-1"
		)).status()).isEqualTo(FacilityReportStatus.REJECTED);
		assertThat(service.reviewReport(new ReviewFacilityReportCommand(
			duplicated.id(),
			FacilityReportReviewDecision.MARK_DUPLICATE,
			"admin-1"
		)).status()).isEqualTo(FacilityReportStatus.DUPLICATE);
	}

	@Test
	@DisplayName("신고 검수는 결정값과 검수자 식별자를 요구한다")
	void reviewReportRequiresDecisionAndReviewer() {
		var report = service.createReport(new CreateFacilityReportCommand(
			"anonymous-user-1",
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"검수 대상 신고입니다.",
			null,
			null,
			null
		));

		assertThatThrownBy(() -> service.reviewReport(new ReviewFacilityReportCommand(
			report.id(),
			null,
			"admin-1"
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("검수 결과를 선택해야 합니다.");
		assertThatThrownBy(() -> service.reviewReport(new ReviewFacilityReportCommand(
			report.id(),
			FacilityReportReviewDecision.ACCEPT,
			""
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("검수자 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("존재하지 않는 신고는 검수할 수 없다")
	void reviewReportRequiresExistingReport() {
		assertThatThrownBy(() -> service.reviewReport(new ReviewFacilityReportCommand(
			"missing-report",
			FacilityReportReviewDecision.ACCEPT,
			"admin-1"
		)))
			.isInstanceOf(FacilityReportNotFoundException.class)
			.hasMessage("신고 정보를 찾을 수 없습니다.");
	}

	private CreateFacilityReportCommand reportCommand(String userId, String description) {
		return reportCommand(userId, FacilityReportType.BROKEN, description);
	}

	private CreateFacilityReportCommand reportCommand(
		String userId,
		FacilityReportType reportType,
		String description
	) {
		return new CreateFacilityReportCommand(
			userId,
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			reportType,
			description,
			null,
			null,
			null
		);
	}

	private FacilityReportService serviceWithClock(Clock clock) {
		return serviceWithClockAndTransitRepository(clock, new InMemoryTransitMasterRepository());
	}

	private FacilityReportService serviceWithTransitRepository(InMemoryTransitMasterRepository transitRepository) {
		return serviceWithClockAndTransitRepository(
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul")),
			transitRepository
		);
	}

	private FacilityReportService serviceWithClockAndTransitRepository(
		Clock clock,
		InMemoryTransitMasterRepository transitRepository
	) {
		var repository = new InMemoryFacilityReportRepository();
		return new FacilityReportService(
			transitRepository,
			transitRepository,
			repository,
			repository,
			clock
		);
	}

	private ReviewFacilityReportCommand reviewCommand(String reportId, FacilityReportReviewDecision decision) {
		return new ReviewFacilityReportCommand(reportId, decision, "admin-1");
	}

	private AccessibilityFacilityStatus facilityStatus(
		InMemoryTransitMasterRepository transitRepository,
		String facilityId
	) {
		return transitRepository.loadAccessibilityFacilities()
			.stream()
			.filter(facility -> facility.id().equals(facilityId))
			.findFirst()
			.orElseThrow()
			.status();
	}

	private static class TickingClock extends Clock {

		private final ZoneId zone = ZoneId.of("Asia/Seoul");
		private Instant current = Instant.parse("2026-06-12T00:00:00Z");

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			Instant instant = current;
			current = current.plusSeconds(1);
			return instant;
		}
	}
}
