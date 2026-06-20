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
	private final int maxTotalPendingCount;
	private final long maxTotalPendingBytes;
	private final SecureRandom secureRandom;

	@Autowired
	FacilityReportUploadIntents(
		@Value("${easysubway.report.upload.url-ttl-seconds:900}") long ttlSeconds,
		@Value("${easysubway.report.upload.max-bytes:921600}") long maxBytes,
		@Value("${easysubway.report.upload.max-pending-count:20}") int maxPendingCount,
		@Value("${easysubway.report.upload.max-pending-bytes:18432000}") long maxPendingBytes,
		@Value("${easysubway.report.upload.max-total-pending-count:200}") int maxTotalPendingCount,
		@Value("${easysubway.report.upload.max-total-pending-bytes:184320000}") long maxTotalPendingBytes
	) {
		this(
			Clock.systemUTC(),
			Duration.ofSeconds(ttlSeconds),
			maxBytes,
			maxPendingCount,
			maxPendingBytes,
			maxTotalPendingCount,
			maxTotalPendingBytes
		);
	}

	FacilityReportUploadIntents(
		Clock clock,
		Duration ttl,
		long maxBytes,
		int maxPendingCount,
		long maxPendingBytes
	) {
		this(clock, ttl, maxBytes, maxPendingCount, maxPendingBytes, maxPendingCount * 10, maxPendingBytes * 10);
	}

	FacilityReportUploadIntents(
		Clock clock,
		Duration ttl,
		long maxBytes,
		int maxPendingCount,
		long maxPendingBytes,
		int maxTotalPendingCount,
		long maxTotalPendingBytes
	) {
		this.clock = clock;
		this.ttl = ttl;
		this.maxBytes = maxBytes;
		this.maxPendingCount = maxPendingCount;
		this.maxPendingBytes = maxPendingBytes;
		this.maxTotalPendingCount = maxTotalPendingCount;
		this.maxTotalPendingBytes = maxTotalPendingBytes;
		this.secureRandom = new SecureRandom();
	}

	CreatedUploadIntent create(
		String clientSubmissionId,
		String contentType,
		String sha256,
		long sizeBytes
	) {
		return create(clientSubmissionId, contentType, sha256, sizeBytes, objectKey -> {
		});
	}

	CreatedUploadIntent create(
		String clientSubmissionId,
		String contentType,
		String sha256,
		long sizeBytes,
		Consumer<String> deleteExpiredObject
	) {
		cleanupExpired(deleteExpiredObject);
		requireCreateAllowed(clientSubmissionId, contentType, sha256, sizeBytes);
		String uploadId = newUploadId();
		String objectKey = OBJECT_KEY_PREFIX + uploadId + extensionFor(contentType);
		Instant issuedAt = clock.instant();
		Instant expiresAt = issuedAt.plus(ttl);
		String normalizedClientSubmissionId = clientSubmissionId.trim();
		UploadIntent intent = new UploadIntent(
			uploadId,
			normalizedClientSubmissionId,
			objectKey,
			contentType,
			sha256.trim(),
			sizeBytes,
			expiresAt
		);
		intents.put(uploadId, intent);
		return new CreatedUploadIntent(uploadId, objectKey, contentType, sha256.trim(), sizeBytes, issuedAt, expiresAt);
	}

	UploadIntent requireUpload(String uploadId, String contentType, String sha256, long sizeBytes, long actualSizeBytes) {
		UploadIntent intent = intents.get(uploadId);
		if (intent == null || intent.expiresAt().isBefore(clock.instant())) {
			throw invalidUpload();
		}
		if (!intent.contentType().equals(normalizedContentType(contentType))
			|| !intent.sha256().equals(sha256 == null ? null : sha256.trim())
			|| intent.sizeBytes() != sizeBytes
			|| intent.sizeBytes() != actualSizeBytes) {
			throw invalidUpload();
		}
		return intent;
	}

	void consumeObjectKey(String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			return;
		}
		intents.values().removeIf(intent -> intent.objectKey().equals(objectKey.trim()));
	}

	void discardPendingObjectKey(String objectKey, Consumer<String> deleteObject) {
		if (objectKey == null || objectKey.isBlank()) {
			return;
		}
		String normalizedObjectKey = objectKey.trim();
		intents.values().removeIf(intent -> {
			if (!intent.objectKey().equals(normalizedObjectKey)) {
				return false;
			}
			deleteObject.accept(intent.objectKey());
			return true;
		});
	}

	void requirePendingObjectKey(String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			return;
		}
		String normalizedObjectKey = objectKey.trim();
		if (!isUnclaimedObjectKey(normalizedObjectKey)
			|| intents.values().stream().noneMatch(intent -> intent.objectKey().equals(normalizedObjectKey))) {
			throw invalidUpload();
		}
	}

	static boolean isUnclaimedObjectKey(String objectKey) {
		return objectKey != null && objectKey.trim().startsWith(OBJECT_KEY_PREFIX);
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
		String normalizedClientSubmissionId = clientSubmissionId.trim();
		if (pendingCount(normalizedClientSubmissionId) >= maxPendingCount
			|| pendingBytes(normalizedClientSubmissionId) + sizeBytes > maxPendingBytes
			|| intents.size() >= maxTotalPendingCount
			|| totalPendingBytes() + sizeBytes > maxTotalPendingBytes) {
			throw new InvalidFacilityReportException("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
		}
	}

	private long pendingCount(String clientSubmissionId) {
		return intents.values()
			.stream()
			.filter(intent -> intent.clientSubmissionId().equals(clientSubmissionId))
			.count();
	}

	private long pendingBytes(String clientSubmissionId) {
		return intents.values()
			.stream()
			.filter(intent -> intent.clientSubmissionId().equals(clientSubmissionId))
			.mapToLong(UploadIntent::sizeBytes)
			.sum();
	}

	private long totalPendingBytes() {
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

	private String normalizedContentType(String contentType) {
		if (contentType == null) {
			return null;
		}
		return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
	}

	private InvalidFacilityReportException invalidUpload() {
		return new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
	}

	record CreatedUploadIntent(
		String uploadId,
		String objectKey,
		String contentType,
		String sha256,
		long sizeBytes,
		Instant issuedAt,
		Instant expiresAt
	) {
	}

	record UploadIntent(
		String uploadId,
		String clientSubmissionId,
		String objectKey,
		String contentType,
		String sha256,
		long sizeBytes,
		Instant expiresAt
	) {
	}
}
