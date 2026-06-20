package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
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
	@DisplayName("업로드 intent object key는 다른 backend instance에서도 서명으로 검증된다")
	void requirePendingObjectKeyAcceptsSignedObjectKeyWithoutLocalIntent() {
		FacilityReportUploadIntents issuer = signedIntents();
		var intent = issuer.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		FacilityReportUploadIntents verifier = signedIntents();

		assertThatNoException().isThrownBy(() -> verifier.requirePendingObjectKey(
			"client-submission-1",
			intent.objectKey(),
			"image/jpeg",
			"a".repeat(64),
			11L
		));
	}

	@Test
	@DisplayName("업로드 intent object key 서명은 제출 식별자와 metadata가 다르면 거부한다")
	void requirePendingObjectKeyRejectsSignedObjectKeyMetadataMismatch() {
		FacilityReportUploadIntents issuer = signedIntents();
		var intent = issuer.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		FacilityReportUploadIntents verifier = signedIntents();

		assertThatThrownBy(() -> verifier.requirePendingObjectKey(
			"client-submission-2",
			intent.objectKey(),
			"image/jpeg",
			"a".repeat(64),
			11L
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");

		assertThatThrownBy(() -> verifier.requirePendingObjectKey(
			"client-submission-1",
			intent.objectKey(),
			"image/jpeg",
			"b".repeat(64),
			11L
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("업로드 intent object key 서명은 만료 시간이 지나면 거부한다")
	void requirePendingObjectKeyRejectsExpiredSignedObjectKey() {
		FacilityReportUploadIntents issuer = signedIntents();
		var intent = issuer.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		clock.advance(Duration.ofMinutes(16));
		FacilityReportUploadIntents verifier = signedIntents();

		assertThatThrownBy(() -> verifier.requirePendingObjectKey(
			"client-submission-1",
			intent.objectKey(),
			"image/jpeg",
			"a".repeat(64),
			11L
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("업로드 intent는 같은 제출 식별자의 pending 개수와 byte quota를 넘길 수 없다")
	void createIntentRejectsPendingQuotaExceeded() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			1,
			11L
		);

		intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);

		assertThatThrownBy(() -> intents.create("client-submission-1", "image/jpeg", "b".repeat(64), 1L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
	}

	@Test
	@DisplayName("업로드 intent quota는 서로 다른 제출 식별자 사이에서 공유하지 않는다")
	void createIntentAppliesPendingQuotaPerClientSubmission() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			1,
			11L
		);

		var first = intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		var second = intents.create("client-submission-2", "image/jpeg", "b".repeat(64), 11L);

		assertThat(first.objectKey()).isNotEqualTo(second.objectKey());
	}

	@Test
	@DisplayName("업로드 intent는 제출 식별자를 바꿔도 전체 pending quota를 넘길 수 없다")
	void createIntentRejectsGlobalPendingQuotaExceeded() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			10,
			10L * 900L * 1024L,
			2,
			22L
		);

		intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		intents.create("client-submission-2", "image/jpeg", "b".repeat(64), 11L);

		assertThatThrownBy(() -> intents.create("client-submission-3", "image/jpeg", "c".repeat(64), 1L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
	}

	@Test
	@DisplayName("업로드 intent 생성은 만료된 미청구 object를 실제 삭제 callback에 넘긴다")
	void createIntentDeletesExpiredObjectsThroughCallback() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			10,
			10L * 900L * 1024L
		);
		var expired = intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		List<String> deletedObjectKeys = new ArrayList<>();

		clock.advance(Duration.ofMinutes(16));
		var fresh = intents.create(
			"client-submission-2",
			"image/jpeg",
			"b".repeat(64),
			11L,
			deletedObjectKeys::add
		);

		assertThat(deletedObjectKeys).containsExactly(expired.objectKey());
		assertThat(fresh.objectKey()).startsWith("facility-reports/unclaimed/");
	}

	@Test
	@DisplayName("업로드 intent는 신고 생성에서 청구되면 pending quota에서 빠진다")
	void consumeObjectKeyReleasesPendingQuota() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			1,
			11L
		);

		var first = intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		intents.consumeObjectKey(first.objectKey());
		var second = intents.create("client-submission-2", "image/jpeg", "b".repeat(64), 11L);

		assertThat(second.objectKey()).startsWith("facility-reports/unclaimed/");
	}

	@Test
	@DisplayName("중복 제출 pending object 정리 실패는 응답 경로를 막지 않고 만료 정리 대상으로 남긴다")
	void discardPendingObjectKeyKeepsResponsePathWhenDeleteFails() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			10,
			10L * 900L * 1024L
		);
		var intent = intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		List<String> deletedObjectKeys = new ArrayList<>();

		assertThatNoException().isThrownBy(() -> intents.discardPendingObjectKey(
			intent.objectKey(),
			objectKey -> {
				throw new IllegalStateException("object storage unavailable");
			}
		));

		clock.advance(Duration.ofMinutes(16));
		intents.cleanupExpired(deletedObjectKeys::add);

		assertThat(deletedObjectKeys).containsExactly(intent.objectKey());
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
			"prod-object-storage-access-key",
			"prod-object-storage-secret-key-with-enough-entropy",
			"ap-northeast-2"
		);
		var intent = new FacilityReportUploadIntents.CreatedUploadIntent(
			"upload-id-1",
			"facility-reports/unclaimed/object-1.jpg",
			"image/jpeg",
			"a".repeat(64),
			11L,
			Instant.parse("2026-06-20T00:00:00Z"),
			Instant.parse("2026-06-20T00:15:00Z")
		);

		var signedUrl = signer.sign(intent);

		assertThat(signedUrl.uploadMethod()).isEqualTo("PUT");
		assertThat(signedUrl.uploadUrl()).startsWith(
			"https://object-storage.example.com/easysubway-report-uploads/facility-reports/unclaimed/object-1.jpg"
		);
		assertThat(signedUrl.uploadUrl()).contains("X-Amz-Algorithm=AWS4-HMAC-SHA256");
		assertThat(signedUrl.uploadUrl()).contains("X-Amz-Credential=prod-object-storage-access-key%2F20260620%2Fap-northeast-2%2Fs3%2Faws4_request");
		assertThat(signedUrl.uploadUrl()).contains("X-Amz-Date=20260620T000000Z");
		assertThat(signedUrl.uploadUrl()).contains("X-Amz-Expires=900");
		assertThat(signedUrl.uploadUrl()).contains(
			"X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost%3Bx-amz-content-sha256%3Bx-amz-meta-easysubway-sha256%3Bx-amz-meta-easysubway-size"
		);
		assertThat(signedUrl.uploadUrl()).containsPattern("X-Amz-Signature=[0-9a-f]{64}");
		assertThat(signedUrl.uploadHeaders()).containsEntry("content-length", "11");
		assertThat(signedUrl.uploadHeaders()).containsEntry("content-type", "image/jpeg");
		assertThat(signedUrl.uploadHeaders()).containsEntry("x-amz-content-sha256", "a".repeat(64));
		assertThat(signedUrl.uploadHeaders()).containsEntry("x-amz-meta-easysubway-sha256", "a".repeat(64));
		assertThat(signedUrl.uploadHeaders()).containsEntry("x-amz-meta-easysubway-size", "11");
	}

	@Test
	@DisplayName("개발 upload signer는 backend 상대 upload URL만 반환한다")
	void localUploadSignerReturnsBackendRelativeUrl() {
		var signer = new LocalFacilityReportUploadUrlSigner();
		var intent = new FacilityReportUploadIntents.CreatedUploadIntent(
			"upload-id-1",
			"facility-reports/unclaimed/object-1.jpg",
			"image/jpeg",
			"a".repeat(64),
			11L,
			Instant.parse("2026-06-20T00:00:00Z"),
			Instant.parse("2026-06-20T00:15:00Z")
		);

		var signedUrl = signer.sign(intent);

		assertThat(signedUrl.uploadUrl()).isEqualTo("/api/v1/report-uploads/upload-id-1");
		assertThat(signedUrl.uploadMethod()).isEqualTo("PUT");
	}

	private FacilityReportUploadIntents signedIntents() {
		return new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			10,
			10L * 900L * 1024L,
			100,
			100L * 900L * 1024L,
			"test-upload-intent-signing-key-with-enough-entropy"
		);
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
