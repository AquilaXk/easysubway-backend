package com.easysubway.report.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.report.adapter.out.persistence.InMemoryFacilityReportRepository;
import com.easysubway.report.application.port.in.CreateFacilityReportCommand;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportNotFoundException;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportTargetNotFoundException;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.report.domain.InvalidFacilityReportException;
import com.easysubway.notification.application.port.in.FacilityStatusAlertUseCase;
import com.easysubway.notification.application.port.in.FacilityStatusChangedAlertCommand;
import com.easysubway.notification.application.port.in.ReportStatusAlertUseCase;
import com.easysubway.notification.application.port.in.ReportStatusChangedAlertCommand;
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
	@DisplayName("시설 신고는 사용자 식별자를 요구한다")
	void createReportRequiresUserId() {
		assertThatThrownBy(() -> service.createReport(new CreateFacilityReportCommand(
			"",
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"신고 작성자 식별자가 없는 요청입니다.",
			null,
			null,
			null
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
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
	@DisplayName("내 신고 목록은 요청 사용자 신고만 최신순으로 반환한다")
	void listUserReportsReturnsOnlyUserReportsByNewestFirst() {
		FacilityReportService serviceWithTickingClock = serviceWithClock(new TickingClock());

		var older = serviceWithTickingClock.createReport(reportCommand("report-owner", "먼저 접수한 내 신고"));
		var otherUserReport = serviceWithTickingClock.createReport(reportCommand("other-user", "다른 사용자의 신고"));
		var newer = serviceWithTickingClock.createReport(reportCommand("report-owner", "나중에 접수한 내 신고"));

		assertThat(serviceWithTickingClock.listUserReports("report-owner"))
			.extracting("id")
			.containsExactly(newer.id(), older.id());
		assertThat(serviceWithTickingClock.listUserReports("report-owner"))
			.extracting("id")
			.doesNotContain(otherUserReport.id());
	}

	@Test
	@DisplayName("내 신고 목록은 소유자 없는 신고가 있어도 실패하지 않는다")
	void listUserReportsIgnoresReportsWithoutOwner() {
		reportRepository.saveReport(new FacilityReport(
			"report-without-owner",
			null,
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"소유자 없는 기존 신고입니다.",
			null,
			null,
			null,
			FacilityReportStatus.SUBMITTED,
			LocalDateTime.of(2026, 6, 12, 9, 0),
			null,
			null
		));

		assertThatNoException().isThrownBy(() -> service.listUserReports("anonymous-user-1"));
		assertThat(service.listUserReports("anonymous-user-1")).isEmpty();
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
	@DisplayName("신고 검수 결과는 신고 작성자에게 처리 알림을 요청한다")
	void reviewedReportRequestsReportStatusAlertForReporter() {
		var reportStatusAlertUseCase = new RecordingReportStatusAlertUseCase();
		var repository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			new InMemoryTransitMasterRepository(),
			new InMemoryTransitMasterRepository(),
			repository,
			repository,
			command -> {
			},
			reportStatusAlertUseCase,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
		var report = service.createReport(reportCommand(
			"anonymous-user-report-alert",
			FacilityReportType.INFORMATION_WRONG,
			"신고 처리 결과 알림을 받을 신고입니다."
		));

		service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.REJECT));

		assertThat(reportStatusAlertUseCase.commands)
			.extracting(ReportStatusChangedAlertCommand::userId)
			.containsExactly("anonymous-user-report-alert");
		assertThat(reportStatusAlertUseCase.commands)
			.extracting(ReportStatusChangedAlertCommand::reportId)
			.containsExactly(report.id());
		assertThat(reportStatusAlertUseCase.commands)
			.extracting(ReportStatusChangedAlertCommand::status)
			.containsExactly(FacilityReportStatus.REJECTED);
	}

	@Test
	@DisplayName("이미 같은 상태인 신고 재검수는 처리 알림을 다시 요청하지 않는다")
	void repeatedSameReportReviewDoesNotRequestReportStatusAlertAgain() {
		var reportStatusAlertUseCase = new RecordingReportStatusAlertUseCase();
		var repository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			new InMemoryTransitMasterRepository(),
			new InMemoryTransitMasterRepository(),
			repository,
			repository,
			command -> {
			},
			reportStatusAlertUseCase,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
		var report = service.createReport(reportCommand(
			"anonymous-user-repeat-alert",
			FacilityReportType.INFORMATION_WRONG,
			"같은 결과로 다시 검수할 신고입니다."
		));

		service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.REJECT));
		service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.REJECT));

		assertThat(reportStatusAlertUseCase.commands)
			.extracting(ReportStatusChangedAlertCommand::status)
			.containsExactly(FacilityReportStatus.REJECTED);
	}

	@Test
	@DisplayName("신고 처리 결과 알림 실패는 검수 저장 결과를 실패시키지 않는다")
	void reportStatusAlertFailureDoesNotFailReviewResult() {
		var repository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			new InMemoryTransitMasterRepository(),
			new InMemoryTransitMasterRepository(),
			repository,
			repository,
			command -> {
			},
			command -> {
				throw new IllegalStateException("푸시 알림 발송 실패");
			},
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
		var report = service.createReport(reportCommand(
			"anonymous-user-alert-failure",
			FacilityReportType.INFORMATION_WRONG,
			"알림 실패와 별개로 저장되어야 하는 신고입니다."
		));

		assertThatNoException()
			.isThrownBy(() -> service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.REJECT)));
		assertThat(service.getReport(report.id()).status()).isEqualTo(FacilityReportStatus.REJECTED);
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
	@DisplayName("승인된 시설 상태 신고는 즐겨찾기 알림을 요청한다")
	void acceptedReportRequestsFavoriteFacilityStatusAlert() {
		var alertUseCase = new RecordingFacilityStatusAlertUseCase();
		var transitRepository = new InMemoryTransitMasterRepository();
		var repository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			transitRepository,
			transitRepository,
			repository,
			repository,
			alertUseCase,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		var report = service.createReport(reportCommand(
			"anonymous-user-alert",
			FacilityReportType.BROKEN,
			"알림을 보낼 고장 신고입니다."
		));

		service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.ACCEPT));

		assertThat(alertUseCase.commands)
			.extracting(FacilityStatusChangedAlertCommand::facilityId)
			.containsExactly("facility-sangnoksu-elevator-1");
		assertThat(alertUseCase.commands)
			.extracting(FacilityStatusChangedAlertCommand::status)
			.containsExactly(AccessibilityFacilityStatus.BROKEN);
	}

	@Test
	@DisplayName("시설 상태를 바꾸지 않는 신고 검수는 즐겨찾기 알림을 요청하지 않는다")
	void reviewWithoutFacilityStatusChangeDoesNotRequestFavoriteAlert() {
		var alertUseCase = new RecordingFacilityStatusAlertUseCase();
		var transitRepository = new InMemoryTransitMasterRepository();
		var repository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			transitRepository,
			transitRepository,
			repository,
			repository,
			alertUseCase,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		var rejected = service.createReport(reportCommand(
			"anonymous-user-rejected-alert",
			FacilityReportType.BROKEN,
			"반려할 고장 신고입니다."
		));
		var informationWrong = service.createReport(reportCommand(
			"anonymous-user-info-alert",
			FacilityReportType.INFORMATION_WRONG,
			"위치 설명이 다릅니다."
		));

		service.reviewReport(reviewCommand(rejected.id(), FacilityReportReviewDecision.REJECT));
		service.reviewReport(reviewCommand(informationWrong.id(), FacilityReportReviewDecision.ACCEPT));

		assertThat(alertUseCase.commands).isEmpty();
	}

	@Test
	@DisplayName("이미 같은 상태인 신고 승인은 즐겨찾기 알림을 요청하지 않는다")
	void acceptedReportWithSameFacilityStatusDoesNotRequestFavoriteAlert() {
		var alertUseCase = new RecordingFacilityStatusAlertUseCase();
		var transitRepository = new InMemoryTransitMasterRepository();
		var repository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			transitRepository,
			transitRepository,
			repository,
			repository,
			alertUseCase,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		var report = service.createReport(reportCommand(
			"anonymous-user-same-status",
			FacilityReportType.RECOVERED,
			"이미 정상 상태인 시설입니다."
		));

		service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.ACCEPT));

		assertThat(alertUseCase.commands).isEmpty();
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

	private static class RecordingFacilityStatusAlertUseCase implements FacilityStatusAlertUseCase {

		private final java.util.List<FacilityStatusChangedAlertCommand> commands = new java.util.ArrayList<>();

		@Override
		public void alertFacilityStatusChanged(FacilityStatusChangedAlertCommand command) {
			commands.add(command);
		}
	}

	private static class RecordingReportStatusAlertUseCase implements ReportStatusAlertUseCase {

		private final java.util.List<ReportStatusChangedAlertCommand> commands = new java.util.ArrayList<>();

		@Override
		public void alertReportStatusChanged(ReportStatusChangedAlertCommand command) {
			commands.add(command);
		}
	}
}
