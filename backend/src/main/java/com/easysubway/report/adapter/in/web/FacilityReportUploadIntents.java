package com.easysubway.report.adapter.in.web;

import com.easysubway.report.domain.InvalidFacilityReportException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class FacilityReportUploadIntents {

	private static final int UPLOAD_ID_BYTES = 24;
	private static final String OBJECT_KEY_PREFIX = "facility-reports/unclaimed/";

	private final Map<String, UploadIntent> intents = new ConcurrentHashMap<>();
	private final Clock clock;
	private final Duration ttl;
	private final long maxBytes;
	private final int maxPendingCount;
	private final long maxPendingBytes;
	private final SecureRandom secureRandom;

	@Autowired
	FacilityReportUploadIntents(
		@Value("${easysubway.report.upload.url-ttl-seconds:900}") long ttlSeconds,
		@Value("${easysubway.report.upload.max-bytes:921600}") long maxBytes,
		@Value("${easysubway.report.upload.max-pending-count:20}") int maxPendingCount,
		@Value("${easysubway.report.upload.max-pending-bytes:18432000}") long maxPendingBytes
	) {
		this(Clock.systemUTC(), Duration.ofSeconds(ttlSeconds), maxBytes, maxPendingCount, maxPendingBytes);
	}

	FacilityReportUploadIntents(
		Clock clock,
		Duration ttl,
		long maxBytes,
		int maxPendingCount,
		long maxPendingBytes
	) {
		this.clock = clock;
		this.ttl = ttl;
		this.maxBytes = maxBytes;
		this.maxPendingCount = maxPendingCount;
		this.maxPendingBytes = maxPendingBytes;
		this.secureRandom = new SecureRandom();
	}

	CreatedUploadIntent create(
		String clientSubmissionId,
		String contentType,
		String sha256,
		long sizeBytes
	) {
		cleanupExpired(objectKey -> {
		});
		requireCreateAllowed(clientSubmissionId, contentType, sha256, sizeBytes);
		String uploadId = newUploadId();
		String objectKey = OBJECT_KEY_PREFIX + uploadId + extensionFor(contentType);
		Instant expiresAt = clock.instant().plus(ttl);
		UploadIntent intent = new UploadIntent(uploadId, objectKey, contentType, sha256.trim(), sizeBytes, expiresAt);
		intents.put(uploadId, intent);
		return new CreatedUploadIntent(uploadId, objectKey, expiresAt);
	}

	UploadIntent requireUpload(String uploadId, String contentType, String sha256, long sizeBytes, long actualSizeBytes) {
		UploadIntent intent = intents.get(uploadId);
		if (intent == null || intent.expiresAt().isBefore(clock.instant())) {
			throw invalidUpload();
		}
		if (!intent.contentType().equals(contentType == null ? null : contentType.trim().toLowerCase(Locale.ROOT))
			|| !intent.sha256().equals(sha256 == null ? null : sha256.trim())
			|| intent.sizeBytes() != sizeBytes
			|| intent.sizeBytes() != actualSizeBytes) {
			throw invalidUpload();
		}
		return intent;
	}

	void cleanupExpired(Consumer<String> deleteObject) {
		Instant now = clock.instant();
		intents.values().removeIf(intent -> {
			if (intent.expiresAt().isAfter(now)) {
				return false;
			}
			deleteObject.accept(intent.objectKey());
			return true;
		});
	}

	private void requireCreateAllowed(String clientSubmissionId, String contentType, String sha256, long sizeBytes) {
		if (clientSubmissionId == null || clientSubmissionId.isBlank()) {
			throw new InvalidFacilityReportException("신고 제출 식별자가 필요합니다.");
		}
		if (!"image/jpeg".equals(contentType) && !"image/png".equals(contentType) && !"image/webp".equals(contentType)) {
			throw new InvalidFacilityReportException("사진 파일 형식을 확인해야 합니다.");
		}
		if (sha256 == null || !sha256.trim().matches("[0-9a-f]{64}")) {
			throw invalidUpload();
		}
		if (sizeBytes < 1 || sizeBytes > maxBytes) {
			throw new InvalidFacilityReportException("사진 파일 크기를 줄여야 합니다.");
		}
		if (intents.size() >= maxPendingCount || pendingBytes() + sizeBytes > maxPendingBytes) {
			throw new InvalidFacilityReportException("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
		}
	}

	private long pendingBytes() {
		return intents.values()
			.stream()
			.mapToLong(UploadIntent::sizeBytes)
			.sum();
	}

	private String newUploadId() {
		byte[] bytes = new byte[UPLOAD_ID_BYTES];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String extensionFor(String contentType) {
		return switch (contentType) {
			case "image/png" -> ".png";
			case "image/webp" -> ".webp";
			default -> ".jpg";
		};
	}

	private InvalidFacilityReportException invalidUpload() {
		return new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
	}

	record CreatedUploadIntent(String uploadId, String objectKey, Instant expiresAt) {
	}

	record UploadIntent(String uploadId, String objectKey, String contentType, String sha256, long sizeBytes, Instant expiresAt) {
	}
}
