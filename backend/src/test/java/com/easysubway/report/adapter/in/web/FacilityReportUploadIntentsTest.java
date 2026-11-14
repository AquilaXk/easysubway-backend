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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
	@DisplayName("업로드 intent object key 서명 검증은 content type 누락을 400 경계 예외로 거부한다")
	void requirePendingObjectKeyRejectsMissingContentTypeBeforeSignedKeyParsing() {
		FacilityReportUploadIntents issuer = signedIntents();
		var intent = issuer.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		FacilityReportUploadIntents verifier = signedIntents();

		assertThatThrownBy(() -> verifier.requirePendingObjectKey(
			"client-submission-1",
			intent.objectKey(),
			null,
			"a".repeat(64),
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
	@DisplayName("업로드 intent는 제출 식별자의 길이와 문자를 제한한다")
	void createIntentRejectsInvalidClientSubmissionId() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			10,
			10L * 900L * 1024L
		);

		assertThatThrownBy(() -> intents.create("a".repeat(121), "image/jpeg", "a".repeat(64), 11L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("신고 제출 식별자를 확인해야 합니다.");

		assertThatThrownBy(() -> intents.create("client/submission/1", "image/jpeg", "a".repeat(64), 11L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("신고 제출 식별자를 확인해야 합니다.");
	}

	@Test
	@DisplayName("업로드 intent quota는 동시 요청에서도 check-insert 경계를 보장한다")
	void createIntentKeepsPendingQuotaUnderConcurrentRequests() throws Exception {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			1,
			11L
		);
		int requestCount = 8;
		var executor = Executors.newFixedThreadPool(requestCount);
		CountDownLatch ready = new CountDownLatch(requestCount);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger created = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

		for (int index = 0; index < requestCount; index++) {
			int requestIndex = index;
			futures.add(executor.submit(() -> {
				ready.countDown();
				try {
					assertThat(start.await(1, TimeUnit.SECONDS)).isTrue();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
				try {
					intents.create("client-submission-1", "image/jpeg", Integer.toHexString(requestIndex).repeat(64), 11L);
					created.incrementAndGet();
				} catch (InvalidFacilityReportException exception) {
					assertThat(exception).hasMessage("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
					rejected.incrementAndGet();
				}
			}));
		}

		assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
		start.countDown();
		for (var future : futures) {
			future.get(2, TimeUnit.SECONDS);
		}
		executor.shutdownNow();

		assertThat(created).hasValue(1);
		assertThat(rejected).hasValue(requestCount - 1);
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
	@DisplayName("만료 object 삭제 실패는 새 upload intent 발급을 막지 않는다")
	void createIntentIgnoresExpiredObjectDeleteFailure() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			10,
			10L * 900L * 1024L,
			1,
			11L
		);
		var expired = intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		List<String> deleteAttempts = new ArrayList<>();

		clock.advance(Duration.ofMinutes(16));
		var fresh = intents.create(
			"client-submission-2",
			"image/jpeg",
			"b".repeat(64),
			11L,
			objectKey -> {
				deleteAttempts.add(objectKey);
				throw new IllegalStateException("object storage unavailable");
			}
		);

		assertThat(deleteAttempts).containsExactly(expired.objectKey());
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
			"client-submission-1",
			intent.objectKey(),
			"image/jpeg",
			"a".repeat(64),
			11L,
			objectKey -> {
				throw new IllegalStateException("object storage unavailable");
			}
		));

		clock.advance(Duration.ofMinutes(16));
		intents.cleanupExpired(deletedObjectKeys::add);

		assertThat(deletedObjectKeys).containsExactly(intent.objectKey());
	}

	@Test
	@DisplayName("중복 제출 pending object는 local intent가 없어도 서명 검증 후 삭제한다")
	void discardPendingObjectKeyDeletesSignedObjectKeyWithoutLocalIntent() {
		FacilityReportUploadIntents issuer = signedIntents();
		var intent = issuer.create("client-submission-1", "image/jpeg", "a".repeat(64), 11L);
		FacilityReportUploadIntents verifier = signedIntents();
		List<String> deletedObjectKeys = new ArrayList<>();

		verifier.discardPendingObjectKey(
			"client-submission-1",
			intent.objectKey(),
			"image/jpeg",
			"a".repeat(64),
			11L,
			deletedObjectKeys::add
		);

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
	@DisplayName("운영 upload signer는 공개 HTTPS origin에 만료와 서명을 붙인다")
	void prodUploadSignerReturnsSignedAbsoluteObjectStorageUrl() {
		var signer = new ObjectStorageFacilityReportUploadUrlSigner(
			"https://uploads.easysubway.example",
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
			"https://uploads.easysubway.example/easysubway-report-uploads/facility-reports/unclaimed/object-1.jpg"
		);
		assertThat(signedUrl.uploadUrl()).doesNotContain("object-storage:9000");
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
	@DisplayName("운영 upload signer는 내부 Docker hostname을 공개 origin으로 쓰지 않는다")
	void prodUploadSignerRejectsInternalPublicOrigin() {
		assertThatThrownBy(() -> new ObjectStorageFacilityReportUploadUrlSigner(
			"http://object-storage:9000",
			"easysubway-report-uploads",
			"prod-object-storage-access-key",
			"prod-object-storage-secret-key-with-enough-entropy",
			"ap-northeast-2"
		).sign(new FacilityReportUploadIntents.CreatedUploadIntent(
			"upload-id-1",
			"facility-reports/unclaimed/object-1.jpg",
			"image/jpeg",
			"a".repeat(64),
			11L,
			Instant.parse("2026-06-20T00:00:00Z"),
			Instant.parse("2026-06-20T00:15:00Z")
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("운영 report upload public base URL은 HTTPS origin이어야 합니다.");

		assertThatThrownBy(() -> new ObjectStorageFacilityReportUploadUrlSigner(
			"https://[::1]",
			"easysubway-report-uploads",
			"prod-object-storage-access-key",
			"prod-object-storage-secret-key-with-enough-entropy",
			"ap-northeast-2"
		).sign(new FacilityReportUploadIntents.CreatedUploadIntent(
			"upload-id-1",
			"facility-reports/unclaimed/object-1.jpg",
			"image/jpeg",
			"a".repeat(64),
			11L,
			Instant.parse("2026-06-20T00:00:00Z"),
			Instant.parse("2026-06-20T00:15:00Z")
		)))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("운영 report upload public base URL은 HTTPS origin이어야 합니다.");
	}

	@Test
	@DisplayName("만료 정리 삭제 콜백이 블로킹되어도 executor로 비동기 실행되어 intent 생성 락을 막지 않는다")
	void cleanupExpiredOffloadsDeleteOutsideIntentLock() throws Exception {
		var asyncExecutor = Executors.newSingleThreadExecutor();
		try {
			FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
				clock,
				Duration.ofMinutes(15),
				900L * 1024L,
				10,
				10L * 900L * 1024L,
				2000,
				100L * 900L * 1024L
			);
			intents.useExpiredDeleteExecutorForTest(asyncExecutor);
			var expired = intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 100L);
			clock.advance(Duration.ofMinutes(16));

			var deleteStarted = new CountDownLatch(1);
			var releaseDelete = new CountDownLatch(1);
			List<String> deletedKeys = new ArrayList<>();

			var created = intents.create("client-submission-2", "image/jpeg", "b".repeat(64), 100L, objectKey -> {
				deleteStarted.countDown();
				try {
					releaseDelete.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				synchronized (deletedKeys) {
					deletedKeys.add(objectKey);
				}
			});

			assertThat(deleteStarted.await(5, TimeUnit.SECONDS)).isTrue();
			var concurrent = intents.create("client-submission-3", "image/jpeg", "c".repeat(64), 100L);
			assertThat(concurrent.objectKey()).isNotEqualTo(created.objectKey());

			releaseDelete.countDown();
			asyncExecutor.shutdown();
			assertThat(asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
			assertThat(deletedKeys).containsExactly(expired.objectKey());
		} finally {
			asyncExecutor.shutdownNow();
		}
	}

	@Test
	@DisplayName("O(1) pending 카운터는 consume·discard·expire 이후 사용자별 및 전체 한도를 정확히 해제한다")
	void o1PendingCountersReleaseQuotaAfterConsumeDiscardAndExpire() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			1_000L,
			2,
			150L,
			3,
			250L
		);
		var first = intents.create("sub-1", "image/jpeg", "a".repeat(64), 100L);
		var second = intents.create("sub-1", "image/jpeg", "b".repeat(64), 50L);

		assertThatThrownBy(() -> intents.create("sub-1", "image/jpeg", "c".repeat(64), 1L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");

		intents.consumeObjectKey(first.objectKey());
		var third = intents.create("sub-1", "image/jpeg", "c".repeat(64), 100L);
		var fourth = intents.create("sub-2", "image/jpeg", "d".repeat(64), 100L);

		assertThatThrownBy(() -> intents.create("sub-3", "image/jpeg", "e".repeat(64), 1L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");

		intents.discardPendingObjectKey("sub-2", fourth.objectKey(), "image/jpeg", "d".repeat(64), 100L, ignored -> {
		});
		var fifth = intents.create("sub-3", "image/jpeg", "e".repeat(64), 100L);
		assertThat(fifth.objectKey()).isNotEqualTo(third.objectKey());
		assertThat(second.objectKey()).isNotEqualTo(third.objectKey());

		clock.advance(Duration.ofMinutes(16));
		intents.cleanupExpired(ignored -> {
		});
		var afterExpire = intents.create("sub-1", "image/jpeg", "f".repeat(64), 150L);
		assertThat(afterExpire.sizeBytes()).isEqualTo(150L);

		// 존재하지 않는 object key 소비는 조용히 무시되고, 4-arg create는 만료 시 기본 no-op delete를 탄다
		intents.consumeObjectKey("facility-reports/unclaimed/missing.jpg");
		clock.advance(Duration.ofMinutes(16));
		var reloadedAfterNoopCleanup = intents.create("sub-1", "image/jpeg", "1".repeat(64), 100L);
		assertThatThrownBy(() -> intents.create("sub-1", "image/jpeg", "2".repeat(64), 51L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
		intents.create("sub-4", "image/jpeg", "3".repeat(64), 100L);
		assertThatThrownBy(() -> intents.create("sub-5", "image/jpeg", "4".repeat(64), 51L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
		assertThat(reloadedAfterNoopCleanup.sizeBytes()).isEqualTo(100L);
		intents.shutdown();
	}

	@Test
	@DisplayName("기본 만료 삭제 executor는 데몬 스레드로 삭제를 실행하고 포화 시 드롭하며 shutdown으로 종료된다")
	void defaultExpiredDeleteExecutorRunsDaemonThreadDropsWhenFullAndShutsDown() throws Exception {
		var springIntents = new FacilityReportUploadIntents(
			0L,
			1_000L,
			10,
			10_000L,
			100,
			100_000L,
			"test-upload-intent-signing-key-with-enough-entropy"
		);
		try {
			var deletedKeys = new ArrayList<String>();
			var deletedLatch = new CountDownLatch(1);
			var first = springIntents.create("sub-spring-1", "image/jpeg", "a".repeat(64), 10L);
			springIntents.create("sub-spring-2", "image/jpeg", "b".repeat(64), 10L, key -> {
				synchronized (deletedKeys) {
					deletedKeys.add(key);
				}
				deletedLatch.countDown();
			});
			assertThat(deletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(deletedKeys).containsExactly(first.objectKey());
		} finally {
			springIntents.shutdown();
		}

		var boundedExecutor = FacilityReportUploadIntents.createExpiredDeleteExecutor(1);
		var workerStarted = new CountDownLatch(1);
		var releaseWorker = new CountDownLatch(1);
		try {
			boundedExecutor.execute(() -> {
				workerStarted.countDown();
				try {
					releaseWorker.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			});
			assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
			boundedExecutor.execute(() -> {
			});
			// queueCapacity=1이 꽉 찬 상태에서 세 번째 작업은 예외 없이 드롭된다
			assertThatNoException().isThrownBy(() -> boundedExecutor.execute(() -> {
			}));
		} finally {
			releaseWorker.countDown();
			boundedExecutor.shutdownNow();
		}
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

	@Test
	@DisplayName("pending object 정리 중 이미 소비된 intent는 quota를 중복 차감하지 않는다")
	void discardPendingObjectKeyDoesNotDoubleDecrementWhenConsumedConcurrently() {
		FacilityReportUploadIntents intents = new FacilityReportUploadIntents(
			clock,
			Duration.ofMinutes(15),
			900L * 1024L,
			2,
			20L,
			2,
			20L,
			"test-upload-intent-signing-key-with-enough-entropy"
		);
		var first = intents.create("client-submission-1", "image/jpeg", "a".repeat(64), 10L);
		intents.create("client-submission-1", "image/jpeg", "b".repeat(64), 10L);

		intents.discardPendingObjectKey(
			"client-submission-1",
			first.objectKey(),
			"image/jpeg",
			"a".repeat(64),
			10L,
			intents::consumeObjectKey
		);

		intents.create("client-submission-1", "image/jpeg", "c".repeat(64), 10L);
		assertThatThrownBy(() -> intents.create("client-submission-1", "image/jpeg", "d".repeat(64), 1L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
		assertThatThrownBy(() -> intents.create("client-submission-2", "image/jpeg", "e".repeat(64), 1L))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
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
