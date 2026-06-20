package com.easysubway.report.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.report.adapter.out.persistence.InMemoryFacilityReportRepository;
import com.easysubway.report.adapter.out.storage.LocalFacilityReportPhotoStorage;
import com.easysubway.report.application.port.in.CreateFacilityReportCommand;
import com.easysubway.report.application.port.in.CreatedFacilityReport;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.application.port.out.DeleteFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.LoadFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.LoadFacilityReportPhotoPort.LoadedFacilityReportPhoto;
import com.easysubway.report.application.port.out.StoreFacilityReportUploadedPhotoPort.StoreUploadedReportPhotoCommand;
import com.easysubway.report.application.port.out.LoadFacilityReportReviewAuditPort;
import com.easysubway.report.application.port.out.SaveFacilityReportReviewAuditPort;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportNotFoundException;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportReviewConflictException;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
			null,
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
			null,
			null,
			null
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("receipt token은 제출 식별자가 아니라 안전한 난수에서 생성한다")
	void receiptTokenUsesRandomEntropyInsteadOfClientSubmissionId() {
		FacilityReportReceiptTokens receiptTokens = new FacilityReportReceiptTokens(
			"test-receipt-token-pepper-with-enough-entropy"
		);

		var first = receiptTokens.issue("client-submission-random-1");
		var second = receiptTokens.issue("client-submission-random-1");

		assertThat(first.token()).isNotEqualTo(second.token());
		assertThat(first.hash()).isNotEqualTo(second.hash());
		assertThat(receiptTokens.matches(first.token(), first.hash())).isTrue();
		assertThat(receiptTokens.matches(second.token(), second.hash())).isTrue();
	}

	@Test
	@DisplayName("receipt token 검증은 기존 SHA-256 hash를 유지 호환한다")
	void receiptTokenMatchesLegacySha256Hash() {
		FacilityReportReceiptTokens receiptTokens = new FacilityReportReceiptTokens(
			"test-receipt-token-pepper-with-enough-entropy"
		);
		String token = "legacy-receipt-token";
		String legacyHash = sha256Hex(
			"receipt-token-hash:test-receipt-token-pepper-with-enough-entropy:legacy-receipt-token"
				.getBytes(StandardCharsets.UTF_8)
		);

		assertThat(receiptTokens.matches(token, legacyHash)).isTrue();
		assertThat(receiptTokens.hash(token)).isNotEqualTo(legacyHash);
	}

	@Test
	@DisplayName("운영 프로필은 강한 receipt token pepper가 없으면 시작하지 않는다")
	void prodProfileFailsWithoutStrongReceiptTokenPepper() {
		new ApplicationContextRunner()
			.withUserConfiguration(ProductionReportReceiptTokenPepperValidator.class)
			.withPropertyValues("spring.profiles.active=prod")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseMessage("운영 receipt token pepper 설정이 필요합니다.");
			});
	}

	@Test
	@DisplayName("시설 신고 사진은 허용된 이미지 형식만 저장한다")
	void createReportRequiresAllowedPhotoContentType() {
		assertThatThrownBy(() -> service.createReport(photoReportCommand(
			"memo.txt",
			"text/plain",
			"aW1hZ2UtYnl0ZXM="
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 파일 형식을 확인해야 합니다.");
	}

	@Test
	@DisplayName("시설 신고 사진은 저장 전에 공백과 형식을 정리한다")
	void createReportNormalizesPhotoFieldsBeforeSaving() {
		FacilityReport report = service.createReport(photoReportCommand(
			" elevator.jpg ",
			" IMAGE/JPEG ",
			" " + validJpegBase64() + " "
		));

		assertThat(report.photoFileName()).isEqualTo("elevator.jpg");
		assertThat(report.photoContentType()).isEqualTo("image/jpeg");
		assertThat(report.photoObjectKey()).startsWith("facility-reports/" + report.id() + "/");
		assertThat(report.photoThumbnailObjectKey()).startsWith("facility-reports/" + report.id() + "/");
		assertThat(report.photoSha256()).matches("[0-9a-f]{64}");
		assertThat(report.photoSizeBytes()).isPositive();
	}

	@Test
	@DisplayName("시설 신고 사진은 서버 크기 제한을 넘을 수 없다")
	void createReportRejectsOversizedPhotoPayload() {
		String largePhotoBase64 = Base64.getEncoder().encodeToString(new byte[(900 * 1024) + 1]);

		assertThatThrownBy(() -> service.createReport(photoReportCommand(
			"large.jpg",
			"image/jpeg",
			largePhotoBase64
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 파일 크기를 줄여야 합니다.");
	}

	@Test
	@DisplayName("시설 신고 사진은 올바른 base64 본문을 요구한다")
	void createReportRequiresValidPhotoPayload() {
		assertThatThrownBy(() -> service.createReport(photoReportCommand(
			"broken.jpg",
			"image/jpeg",
			"not-base64"
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("시설 신고 object 사진은 업로드된 객체를 요구한다")
	void createReportRequiresUploadedPhotoObject() {
		assertThatThrownBy(() -> service.createReport(objectPhotoReportCommand(
			"facility-reports/unclaimed/client-submission-1-photo.jpg",
			"2c8648d103e3dd7ad87660da0f126a1443b6d21ac1bd3ec000c5e24e2373a90c",
			11L
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("시설 신고 object 사진은 업로드 객체 무결성을 검증한다")
	void createReportVerifiesUploadedPhotoObjectMetadata() {
		byte[] jpegBytes = validJpegBytes();
		FacilityReportService service = serviceWithUploadedPhoto(
			"facility-reports/unclaimed/client-submission-1-photo.jpg",
			"image/jpeg",
			jpegBytes
		);

		assertThatNoException().isThrownBy(() -> service.createReport(objectPhotoReportCommand(
			"facility-reports/unclaimed/client-submission-1-photo.jpg",
			sha256Hex(jpegBytes),
			(long) jpegBytes.length
		)));
		assertThatThrownBy(() -> service.createReport(objectPhotoReportCommand(
			"client-submission-2",
			"facility-reports/unclaimed/client-submission-1-photo.jpg",
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			(long) jpegBytes.length
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("시설 신고 object 사진은 신고 생성 성공 후 같은 업로드 객체를 다시 사용할 수 없다")
	void createReportClaimsUploadedPhotoObjectOnce() throws IOException {
		byte[] jpegBytes = validJpegBytes();
		String objectKey = "facility-reports/unclaimed/client-submission-once-photo.jpg";
		LocalFacilityReportPhotoStorage storage = new LocalFacilityReportPhotoStorage(
			Files.createTempDirectory("facility-report-photo-once-")
		);
		storage.storeUploadedReportPhoto(new StoreUploadedReportPhotoCommand(objectKey, jpegBytes));
		FacilityReportService service = serviceWithPhotoStorage(storage);

		assertThatNoException().isThrownBy(() -> service.createReport(objectPhotoReportCommand(
			"client-submission-once-1",
			objectKey,
			sha256Hex(jpegBytes),
			(long) jpegBytes.length
		)));

		assertThatThrownBy(() -> service.createReport(objectPhotoReportCommand(
			"client-submission-once-2",
			objectKey,
			sha256Hex(jpegBytes),
			(long) jpegBytes.length
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("시설 신고 object 사진 원본 삭제 실패는 receipt token 응답을 막지 않는다")
	void createReportWithReceiptReturnsTokenWhenClaimCleanupFails() {
		byte[] jpegBytes = validJpegBytes();
		String objectKey = "facility-reports/unclaimed/client-submission-cleanup-fails-photo.jpg";
		ThrowingDeletePhotoLoader photoLoader = new ThrowingDeletePhotoLoader(objectKey, "image/jpeg", jpegBytes);
		FacilityReportService service = serviceWithPhotoLoader(photoLoader);

		CreatedFacilityReport created = service.createReportWithReceipt(objectPhotoReportCommand(
			"client-submission-cleanup-fails",
			objectKey,
			sha256Hex(jpegBytes),
			(long) jpegBytes.length
		));

		assertThat(created.report().id()).isNotBlank();
		assertThat(created.receiptToken()).isNotBlank();
		assertThat(created.report().photoObjectKey()).startsWith("facility-reports/" + created.report().id() + "/");
		assertThat(photoLoader.deleteAttempted).isTrue();
	}

	@Test
	@DisplayName("시설 신고 object 사진은 최종 저장 경로의 객체를 재사용하거나 삭제하지 않는다")
	void createReportRejectsFinalStoredPhotoObjectKey() throws IOException {
		byte[] jpegBytes = validJpegBytes();
		String objectKey = "facility-reports/report-existing/final-photo.jpg";
		LocalFacilityReportPhotoStorage storage = new LocalFacilityReportPhotoStorage(
			Files.createTempDirectory("facility-report-final-photo-")
		);
		storage.storeUploadedReportPhoto(new StoreUploadedReportPhotoCommand(objectKey, jpegBytes));
		FacilityReportService service = serviceWithPhotoStorage(storage);

		assertThatThrownBy(() -> service.createReport(objectPhotoReportCommand(
			"client-submission-final-key-1",
			objectKey,
			sha256Hex(jpegBytes),
			(long) jpegBytes.length
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
		assertThat(storage.loadFacilityReportPhoto(objectKey)).isPresent();
	}

	@Test
	@DisplayName("시설 신고 object 사진은 이미지가 아닌 업로드 객체를 거부한다")
	void createReportRejectsNonImageUploadedPhotoObject() {
		byte[] invalidBytes = "image-bytes".getBytes(StandardCharsets.UTF_8);
		FacilityReportService service = serviceWithUploadedPhoto(
			"facility-reports/unclaimed/client-submission-1-photo.jpg",
			"image/jpeg",
			invalidBytes
		);

		assertThatThrownBy(() -> service.createReport(objectPhotoReportCommand(
			"facility-reports/unclaimed/client-submission-1-photo.jpg",
			sha256Hex(invalidBytes),
			(long) invalidBytes.length
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
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
	@DisplayName("내 신고 목록은 익명화된 신고가 사용자명과 같아도 반환하지 않는다")
	void listUserReportsExcludeAnonymizedReportsEvenWhenTombstoneMatchesUserId() {
		reportRepository.saveReport(new FacilityReport(
			"report-anonymized",
			FacilityReport.ANONYMIZED_USER_ID,
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"사용자 데이터 삭제로 신고 내용이 삭제되었습니다.",
			null,
			null,
			null,
			null,
			null,
			null,
			FacilityReportStatus.SUBMITTED,
			LocalDateTime.of(2026, 6, 12, 9, 0),
			null,
			null
		));

		assertThat(service.listUserReports(FacilityReport.ANONYMIZED_USER_ID)).isEmpty();
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
	@DisplayName("사용자 신고 익명화는 소유자 없는 신고를 건너뛴다")
	void anonymizeFacilityReportsByUserIdIgnoresReportsWithoutOwner() {
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
			null,
			null,
			null,
			FacilityReportStatus.SUBMITTED,
			LocalDateTime.of(2026, 6, 12, 9, 0),
			null,
			null
		));

		assertThatNoException()
			.isThrownBy(() -> reportRepository.anonymizeFacilityReportsByUserId("anonymous-user-1"));
		assertThat(reportRepository.anonymizeFacilityReportsByUserId("anonymous-user-1")).isZero();
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
	@DisplayName("신고 상태별 집계는 목록 조회 없이 상태별 개수를 반환한다")
	void countReportsByStatusReturnsStatusCounts() {
		FacilityReportService serviceWithTickingClock = serviceWithClock(new TickingClock());

		var submitted = serviceWithTickingClock.createReport(reportCommand("anonymous-user-submitted", "검수 대기 신고"));
		var accepted = serviceWithTickingClock.createReport(reportCommand("anonymous-user-accepted", "승인할 신고"));
		serviceWithTickingClock.reviewReport(new ReviewFacilityReportCommand(
			accepted.id(),
			FacilityReportReviewDecision.ACCEPT,
			"admin-1"
		));

		assertThat(serviceWithTickingClock.countReportsByStatus())
			.containsExactlyInAnyOrderEntriesOf(Map.of(
				FacilityReportStatus.SUBMITTED, 1L,
				FacilityReportStatus.ACCEPTED, 1L
			))
			.doesNotContainKey(FacilityReportStatus.REJECTED);
		assertThat(serviceWithTickingClock.listReports(FacilityReportStatus.SUBMITTED))
			.extracting("id")
			.containsExactly(submitted.id());
	}

	@Test
	@DisplayName("반복 고장 신고 시설 집계는 같은 시설의 고장 신고가 2건 이상인 시설만 반환한다")
	void listRepeatedBrokenReportFacilitiesReturnsRepeatedBrokenFacilityCounts() {
		FacilityReportService serviceWithTickingClock = serviceWithClock(new TickingClock());

		serviceWithTickingClock.createReport(reportCommand("anonymous-user-broken-1", "첫 번째 고장 신고"));
		serviceWithTickingClock.createReport(reportCommand("anonymous-user-broken-2", "두 번째 고장 신고"));
		serviceWithTickingClock.createReport(reportCommand(
			"anonymous-user-info",
			FacilityReportType.INFORMATION_WRONG,
			"정보 오류 신고"
		));

		assertThat(serviceWithTickingClock.listRepeatedBrokenReportFacilities())
			.extracting("stationId", "facilityId", "reportCount")
			.containsExactly(tuple("station-sangnoksu", "facility-sangnoksu-elevator-1", 2L));
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
	@DisplayName("신고 검수는 관리자 행동을 감사 로그로 기록한다")
	void reviewReportStoresAuditLogForReviewerDecision() {
		var auditPort = new RecordingFacilityReportReviewAuditPort();
		var repository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			new InMemoryTransitMasterRepository(),
			new InMemoryTransitMasterRepository(),
			repository,
			repository,
			command -> {
			},
			command -> {
			},
			auditPort,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
		var report = service.createReport(reportCommand(
			"anonymous-user-audit",
			FacilityReportType.INFORMATION_WRONG,
			"감사 로그를 남길 신고입니다."
		));

		service.reviewReport(new ReviewFacilityReportCommand(
			report.id(),
			FacilityReportReviewDecision.REJECT,
			"admin-auditor"
		));

		assertThat(auditPort.savedAudits).hasSize(1);
		FacilityReportReviewAudit audit = auditPort.savedAudits.getFirst();
		assertThat(audit.reportId()).isEqualTo(report.id());
		assertThat(audit.reviewerId()).isEqualTo("admin-auditor");
		assertThat(audit.decision()).isEqualTo(FacilityReportReviewDecision.REJECT);
		assertThat(audit.previousStatus()).isEqualTo(FacilityReportStatus.SUBMITTED);
		assertThat(audit.nextStatus()).isEqualTo(FacilityReportStatus.REJECTED);
		assertThat(audit.createdAt()).isEqualTo(LocalDateTime.of(2026, 6, 12, 9, 0));
	}

	@Test
	@DisplayName("감사 로그 저장 실패는 신고 상태 변경을 남기지 않는다")
	void reviewReportKeepsSubmittedStatusWhenAuditSaveFails() {
		var auditPort = new FailingFacilityReportReviewAuditPort();
		var reportStatusAlertUseCase = new RecordingReportStatusAlertUseCase();
		var facilityStatusAlertUseCase = new RecordingFacilityStatusAlertUseCase();
		var repository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			new InMemoryTransitMasterRepository(),
			new InMemoryTransitMasterRepository(),
			repository,
			repository,
			facilityStatusAlertUseCase,
			reportStatusAlertUseCase,
			auditPort,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
		var report = service.createReport(reportCommand(
			"anonymous-user-audit-failure",
			FacilityReportType.BROKEN,
			"감사 로그 저장 실패 시 상태가 바뀌면 안 되는 신고입니다."
		));

		assertThatThrownBy(() -> service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.ACCEPT)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("감사 로그 저장 실패");

		assertThat(service.getReport(report.id()).status()).isEqualTo(FacilityReportStatus.SUBMITTED);
		assertThat(reportStatusAlertUseCase.commands).isEmpty();
		assertThat(facilityStatusAlertUseCase.commands).isEmpty();
	}

	@Test
	@DisplayName("이미 검수된 신고는 다시 검수할 수 없다")
	void reviewedReportCannotBeReviewedAgain() {
		var auditPort = new RecordingFacilityReportReviewAuditPort();
		var repository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			new InMemoryTransitMasterRepository(),
			new InMemoryTransitMasterRepository(),
			repository,
			repository,
			command -> {
			},
			command -> {
			},
			auditPort,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
		var report = service.createReport(reportCommand(
			"anonymous-user-repeat-audit",
			FacilityReportType.INFORMATION_WRONG,
			"반복 검수 감사 로그를 남길 신고입니다."
		));

		service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.REJECT));

		assertThatThrownBy(() -> service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.REJECT)))
			.isInstanceOf(FacilityReportReviewConflictException.class)
			.hasMessage("이미 검수 처리된 신고입니다.");

		assertThat(auditPort.savedAudits)
			.extracting(FacilityReportReviewAudit::previousStatus)
			.containsExactly(FacilityReportStatus.SUBMITTED);
		assertThat(auditPort.savedAudits)
			.extracting(FacilityReportReviewAudit::nextStatus)
			.containsExactly(FacilityReportStatus.REJECTED);
	}

	@Test
	@DisplayName("신고 검수 감사 로그는 신고별로 조회한다")
	void listReportReviewAuditsReturnsOnlyRequestedReportAudits() {
		var auditPort = new RecordingFacilityReportReviewAuditPort();
		var repository = new InMemoryFacilityReportRepository();
		var service = new FacilityReportService(
			new InMemoryTransitMasterRepository(),
			new InMemoryTransitMasterRepository(),
			repository,
			repository,
			command -> {
			},
			command -> {
			},
			auditPort,
			auditPort,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);
		var targetReport = service.createReport(reportCommand(
			"anonymous-user-audit-target",
			FacilityReportType.INFORMATION_WRONG,
			"조회할 감사 로그를 남길 신고입니다."
		));
		var otherReport = service.createReport(reportCommand(
			"anonymous-user-audit-other",
			FacilityReportType.INFORMATION_WRONG,
			"다른 신고입니다."
		));

		service.reviewReport(reviewCommand(targetReport.id(), FacilityReportReviewDecision.REJECT));
		service.reviewReport(reviewCommand(otherReport.id(), FacilityReportReviewDecision.REJECT));

		assertThat(service.listReviewAudits(targetReport.id()))
			.extracting(FacilityReportReviewAudit::reportId)
			.containsExactly(targetReport.id());
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
	@DisplayName("이미 검수된 신고 재검수는 처리 알림을 다시 요청하지 않는다")
	void repeatedReportReviewDoesNotRequestReportStatusAlertAgain() {
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
		assertThatThrownBy(() -> service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.REJECT)))
			.isInstanceOf(FacilityReportReviewConflictException.class)
			.hasMessage("이미 검수 처리된 신고입니다.");

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
		serviceWithFacilityStatus.reviewReport(new ReviewFacilityReportCommand(
			duplicated.id(),
			FacilityReportReviewDecision.MARK_DUPLICATE,
			"admin-1",
			rejected.id()
		));
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
			"admin-1",
			rejected.id()
		)).status()).isEqualTo(FacilityReportStatus.DUPLICATE);
	}

	@Test
	@DisplayName("신고 작성자는 처리 결과 확인으로 검수 완료 신고를 완료 상태로 바꾼다")
	void reporterConfirmsReviewedReportResult() {
		var report = service.createReport(reportCommand(
			"anonymous-user-confirm",
			FacilityReportType.INFORMATION_WRONG,
			"처리 결과를 확인할 신고입니다."
		));
		service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.REJECT));

		var confirmed = service.confirmReportResult(report.id(), "anonymous-user-confirm");

		assertThat(confirmed.status()).isEqualTo(FacilityReportStatus.RESOLVED);
		assertThat(confirmed.reviewedAt()).isEqualTo(LocalDateTime.of(2026, 6, 12, 9, 0));
		assertThat(confirmed.reviewedBy()).isEqualTo("admin-1");
		assertThat(service.getReport(report.id()).status()).isEqualTo(FacilityReportStatus.RESOLVED);
	}

	@Test
	@DisplayName("다른 사용자의 신고 처리 결과는 확인할 수 없다")
	void confirmReportResultRequiresReportOwner() {
		var report = service.createReport(reportCommand(
			"anonymous-user-owner",
			FacilityReportType.INFORMATION_WRONG,
			"다른 사용자가 확인하면 안 되는 신고입니다."
		));
		service.reviewReport(reviewCommand(report.id(), FacilityReportReviewDecision.REJECT));

		assertThatThrownBy(() -> service.confirmReportResult(report.id(), "anonymous-user-other"))
			.isInstanceOf(FacilityReportNotFoundException.class)
			.hasMessage("신고 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("검수 완료 전이나 중복 처리된 신고는 처리 결과를 확인할 수 없다")
	void confirmReportResultRequiresReviewedReport() {
		var submitted = service.createReport(reportCommand(
			"anonymous-user-submitted-confirm",
			FacilityReportType.BROKEN,
			"아직 검수되지 않은 신고입니다."
		));
		var original = service.createReport(reportCommand(
			"anonymous-user-original-confirm",
			FacilityReportType.BROKEN,
			"중복 기준 신고입니다."
		));
		var duplicated = service.createReport(reportCommand(
			"anonymous-user-duplicate-confirm",
			FacilityReportType.BROKEN,
			"중복 처리된 신고입니다."
		));
		service.reviewReport(new ReviewFacilityReportCommand(
			duplicated.id(),
			FacilityReportReviewDecision.MARK_DUPLICATE,
			"admin-1",
			original.id()
		));

		assertThatThrownBy(() -> service.confirmReportResult(submitted.id(), "anonymous-user-submitted-confirm"))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("검수 완료된 신고만 확인할 수 있습니다.");
		assertThatThrownBy(() -> service.confirmReportResult(duplicated.id(), "anonymous-user-duplicate-confirm"))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("검수 완료된 신고만 확인할 수 있습니다.");
	}

	@Test
	@DisplayName("중복 처리된 신고는 기준 신고 식별자를 함께 저장한다")
	void duplicateReportStoresMergedReportId() {
		var original = service.createReport(reportCommand(
			"anonymous-user-original",
			FacilityReportType.BROKEN,
			"먼저 접수된 고장 신고입니다."
		));
		var duplicated = service.createReport(reportCommand(
			"anonymous-user-duplicated",
			FacilityReportType.BROKEN,
			"같은 시설에 대해 다시 들어온 신고입니다."
		));

		var reviewed = service.reviewReport(new ReviewFacilityReportCommand(
			duplicated.id(),
			FacilityReportReviewDecision.MARK_DUPLICATE,
			"admin-1",
			original.id()
		));

		assertThat(reviewed.status()).isEqualTo(FacilityReportStatus.DUPLICATE);
		assertThat(reviewed.duplicateOfReportId()).isEqualTo(original.id());
		assertThat(service.getReport(duplicated.id()).duplicateOfReportId()).isEqualTo(original.id());
	}

	@Test
	@DisplayName("중복 처리는 존재하는 다른 신고를 기준으로 요구한다")
	void duplicateReviewRequiresExistingDifferentReport() {
		var report = service.createReport(reportCommand(
			"anonymous-user-duplicated",
			FacilityReportType.BROKEN,
			"중복 처리할 신고입니다."
		));

		assertThatThrownBy(() -> service.reviewReport(new ReviewFacilityReportCommand(
			report.id(),
			FacilityReportReviewDecision.MARK_DUPLICATE,
			"admin-1",
			"missing-report"
		)))
			.isInstanceOf(FacilityReportNotFoundException.class)
			.hasMessage("신고 정보를 찾을 수 없습니다.");
		assertThatThrownBy(() -> service.reviewReport(new ReviewFacilityReportCommand(
			report.id(),
			FacilityReportReviewDecision.MARK_DUPLICATE,
			"admin-1",
			report.id()
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("기준 신고를 확인해야 합니다.");
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

	private CreateFacilityReportCommand photoReportCommand(
		String photoFileName,
		String photoContentType,
		String photoDataBase64
	) {
		return new CreateFacilityReportCommand(
			"anonymous-user-photo",
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"사진 첨부 신고입니다.",
			photoFileName,
			photoContentType,
			photoDataBase64,
			null,
			null
		);
	}

	private CreateFacilityReportCommand objectPhotoReportCommand(
		String photoObjectKey,
		String photoSha256,
		Long photoSizeBytes
	) {
		return objectPhotoReportCommand("client-submission-1", photoObjectKey, photoSha256, photoSizeBytes);
	}

	private CreateFacilityReportCommand objectPhotoReportCommand(
		String clientSubmissionId,
		String photoObjectKey,
		String photoSha256,
		Long photoSizeBytes
	) {
		return new CreateFacilityReportCommand(
			"anonymous-user-photo",
			clientSubmissionId,
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			FacilityReportType.BROKEN,
			"사진 첨부 신고입니다.",
			"elevator-door.jpg",
			"image/jpeg",
			null,
			photoObjectKey,
			photoSha256,
			photoSizeBytes,
			null,
			null,
			null
		);
	}

	private FacilityReportService serviceWithUploadedPhoto(String objectKey, String contentType, byte[] bytes) {
		return serviceWithPhotoLoader(candidateObjectKey -> objectKey.equals(candidateObjectKey)
			? Optional.of(new LoadedFacilityReportPhoto(contentType, bytes))
			: Optional.empty());
	}

	private FacilityReportService serviceWithPhotoLoader(LoadFacilityReportPhotoPort photoLoader) {
		var repository = new InMemoryFacilityReportRepository();
		return new FacilityReportService(
			transitRepository,
			transitRepository,
			repository,
			repository,
			command -> new com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort.StoredFacilityReportPhoto(
				"facility-reports/%s/%s".formatted(command.reportId(), command.sha256()),
				"facility-reports/%s/%s-thumbnail".formatted(command.reportId(), command.sha256())
			),
			command -> {
			},
			command -> {
			},
			audit -> audit,
			reportId -> List.of(),
			photoLoader,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul")),
			"local-dev-report-receipt-pepper"
		);
	}

	private FacilityReportService serviceWithPhotoStorage(LocalFacilityReportPhotoStorage storage) {
		var repository = new InMemoryFacilityReportRepository();
		return new FacilityReportService(
			transitRepository,
			transitRepository,
			repository,
			repository,
			storage,
			command -> {
			},
			command -> {
			},
			audit -> audit,
			reportId -> List.of(),
			storage,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul")),
			"local-dev-report-receipt-pepper"
		);
	}

	private static final class ThrowingDeletePhotoLoader implements LoadFacilityReportPhotoPort, DeleteFacilityReportPhotoPort {

		private final String objectKey;
		private final String contentType;
		private final byte[] bytes;
		private boolean deleteAttempted;

		private ThrowingDeletePhotoLoader(String objectKey, String contentType, byte[] bytes) {
			this.objectKey = objectKey;
			this.contentType = contentType;
			this.bytes = bytes;
		}

		@Override
		public Optional<LoadedFacilityReportPhoto> loadFacilityReportPhoto(String candidateObjectKey) {
			return objectKey.equals(candidateObjectKey)
				? Optional.of(new LoadedFacilityReportPhoto(contentType, bytes))
				: Optional.empty();
		}

		@Override
		public void deleteFacilityReportPhoto(String candidateObjectKey) {
			deleteAttempted = true;
			throw new IllegalStateException("object delete failed");
		}
	}

	private String validJpegBase64() {
		return Base64.getEncoder().encodeToString(validJpegBytes());
	}

	private byte[] validJpegBytes() {
		try {
			BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ImageIO.write(image, "jpg", output);
			return output.toByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to create test JPEG", exception);
		}
	}

	private String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
		}
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
		return new ReviewFacilityReportCommand(reportId, decision, "admin-1", null);
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

	private static class RecordingFacilityReportReviewAuditPort
		implements SaveFacilityReportReviewAuditPort, LoadFacilityReportReviewAuditPort {

		private final java.util.List<FacilityReportReviewAudit> savedAudits = new java.util.ArrayList<>();

		@Override
		public FacilityReportReviewAudit saveAudit(FacilityReportReviewAudit audit) {
			savedAudits.add(audit);
			return audit;
		}

		@Override
		public java.util.List<FacilityReportReviewAudit> loadAuditsByReportId(String reportId) {
			return savedAudits.stream()
				.filter(audit -> audit.reportId().equals(reportId))
				.toList();
		}
	}

	private static class FailingFacilityReportReviewAuditPort implements SaveFacilityReportReviewAuditPort {

		@Override
		public FacilityReportReviewAudit saveAudit(FacilityReportReviewAudit audit) {
			throw new IllegalStateException("감사 로그 저장 실패");
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
