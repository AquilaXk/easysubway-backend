package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.report.domain.InvalidFacilityReportException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("시설 신고 사진 업로드 intent")
class FacilityReportUploadIntentsTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-06-20T00:00:00Z"));

	@Test
	@DisplayName("업로드 intent는 제출 식별자가 같아도 서로 다른 unclaimed object key를 발급한다")
	void createIntentUsesRandomUnclaimedObjectKey() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			10,
			10L * 900L * 1024L
		);

		var first = intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		var second = intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);

		assertThat(first.objectKey()).startsWith("facility-reports/unclaimed/");
		assertThat(second.objectKey()).startsWith("facility-reports/unclaimed/");
		assertThat(first.objectKey()).isNotEqualTo(second.objectKey());
		assertThat(first.expiresAt()).isEqualTo(Instant.parse("2026-06-20T00:15:00Z"));
	}

	@Test
	@DisplayName("업로드 intent는 만료된 uploadId와 checksum 불일치를 거부한다")
	void requireUploadRejectsExpiredOrMismatchedMetadata() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			10,
			10L * 900L * 1024L
		);
		var intent = intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);

		assertThatThrownBy(() -> intents.requireUpload(intent.uploadId(), "image/jpeg", "b".repeat(64), 11L, 11L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");

		assertThatThrownBy(() -> intents.requireUpload(intent.uploadId(), "image/png", "a".repeat(64), 11L, 11L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");

		clock.advance(Duration.ofMinutes(16));

		assertThatThrownBy(() -> intents.requireUpload(intent.uploadId(), "image/jpeg", "a".repeat(64), 11L, 11L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("업로드 intent는 pending 개수와 byte quota를 넘길 수 없다")
	void createIntentRejectsPendingQuotaExceeded() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			1,
			11L
		);

		intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);

		assertThatThrownBy(() -> intents.create("client-submission-2", "image/jpeg", "b".repeat(64), 1L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
	}

	@Test
	@DisplayName("업로드 intent는 만료된 미청구 object를 정리한다")
	void cleanupExpiredDeletesOrphanObjects() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			10,
			10L * 900L * 1024L
		);
		var intent = intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		List<String> deletedObjectKeys = new ArrayList<>();

		clock.advance(Duration.ofMinutes(16));
		intents.cleanupExpired(deletedObjectKeys::add);

		assertThat(deletedObjectKeys).containsExactly(intent.objectKey());
		assertThatThrownBy(() -> intents.requireUpload(intent.uploadId(), "image/jpeg", "a".repeat(64), 11L, 11L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("운영 upload signer는 object storage absolute URL에 만료와 서명을 붙인다")
	void prodUploadSignerReturnsSignedAbsoluteObjectStorageUrl() {
		var signer = new ObjectStorageFacilityReportUploadUrlSigner(
			"https://object-storage.example.com",
			"easysubway-report-uploads",
			"prod-object-storage-secret-key-with-enough-entropy"
		);
		var intent = new FacilityReportUploadIntents.CreatedUploadIntent(
			"upload-id-1",
			"facility-reports/unclaimed/object-1.jpg",
			Instant.parse("2026-06-20T00:15:00Z")
		);

		var signedUrl = signer.sign(intent);

		assertThat(signedUrl.uploadMethod()).isEqualTo("PUT");
		assertThat(signedUrl.uploadUrl()).startsWith(
			"https://object-storage.example.com/easysubway-report-uploads/facility-reports%2Funclaimed%2Fobject-1.jpg"
		);
		assertThat(signedUrl.uploadUrl()).contains("expiresAt=2026-06-20T00%3A15%3A00Z");
		assertThat(signedUrl.uploadUrl()).containsPattern("signature=[0-9a-f]{64}");
	}

	@Test
	@DisplayName("개발 upload signer는 backend 상대 upload URL만 반환한다")
	void localUploadSignerReturnsBackendRelativeUrl() {
		var signer = new LocalFacilityReportUploadUrlSigner();
		var intent = new FacilityReportUploadIntents.CreatedUploadIntent(
			"upload-id-1",
			"facility-reports/unclaimed/object-1.jpg",
			Instant.parse("2026-06-20T00:15:00Z")
		);

		var signedUrl = signer.sign(intent);

		assertThat(signedUrl.uploadUrl()).isEqualTo("/api/v1/report-uploads/upload-id-1");
		assertThat(signedUrl.uploadMethod()).isEqualTo("PUT");
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}

		private void advance(Duration duration) {
			instant = instant.plus(duration);
		}
	}
}
