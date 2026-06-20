package com.easysubway.report.adapter.in.web;

import com.easysubway.report.domain.InvalidFacilityReportException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class FacilityReportUploadIntents {

	private static final Logger log = LoggerFactory.getLogger(FacilityReportUploadIntents.class);
	private static final int UPLOAD_ID_BYTES = 24;
	private static final String OBJECT_KEY_PREFIX = "facility-reports/unclaimed/";
	private static final String DEFAULT_INTENT_SIGNING_KEY = "local-dev-report-upload-intent-signing-key";

	private final Object intentLock = new Object();
	private final Map<String, UploadIntent> intents = new ConcurrentHashMap<>();
	private final Clock clock;
	private final Duration ttl;
	private final long maxBytes;
	private final int maxPendingCount;
	private final long maxPendingBytes;
	private final int maxTotalPendingCount;
	private final long maxTotalPendingBytes;
	private final SecureRandom secureRandom;
	private final String intentSigningKey;

	@Autowired
	FacilityReportUploadIntents(
		@Value("${easysubway.report.upload.url-ttl-seconds:900}") long ttlSeconds,
		@Value("${easysubway.report.upload.max-bytes:921600}") long maxBytes,
		@Value("${easysubway.report.upload.max-pending-count:20}") int maxPendingCount,
		@Value("${easysubway.report.upload.max-pending-bytes:18432000}") long maxPendingBytes,
		@Value("${easysubway.report.upload.max-total-pending-count:200}") int maxTotalPendingCount,
		@Value("${easysubway.report.upload.max-total-pending-bytes:184320000}") long maxTotalPendingBytes,
		@Value("${easysubway.report.upload.intent-signing-key:${easysubway.report.receipt-token-pepper:local-dev-report-upload-intent-signing-key}}") String intentSigningKey
	) {
		this(
			Clock.systemUTC(),
			Duration.ofSeconds(ttlSeconds),
			maxBytes,
			maxPendingCount,
			maxPendingBytes,
			maxTotalPendingCount,
			maxTotalPendingBytes,
			intentSigningKey
		);
	}

	FacilityReportUploadIntents(
		Clock clock,
		Duration ttl,
		long maxBytes,
		int maxPendingCount,
		long maxPendingBytes
	) {
		this(
			clock,
			ttl,
			maxBytes,
			maxPendingCount,
			maxPendingBytes,
			maxPendingCount * 10,
			maxPendingBytes * 10,
			DEFAULT_INTENT_SIGNING_KEY
		);
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
		this(
			clock,
			ttl,
			maxBytes,
			maxPendingCount,
			maxPendingBytes,
			maxTotalPendingCount,
			maxTotalPendingBytes,
			DEFAULT_INTENT_SIGNING_KEY
		);
	}

	FacilityReportUploadIntents(
		Clock clock,
		Duration ttl,
		long maxBytes,
		int maxPendingCount,
		long maxPendingBytes,
		int maxTotalPendingCount,
		long maxTotalPendingBytes,
		String intentSigningKey
	) {
		this.clock = clock;
		this.ttl = ttl;
		this.maxBytes = maxBytes;
		this.maxPendingCount = maxPendingCount;
		this.maxPendingBytes = maxPendingBytes;
		this.maxTotalPendingCount = maxTotalPendingCount;
		this.maxTotalPendingBytes = maxTotalPendingBytes;
		this.secureRandom = new SecureRandom();
		this.intentSigningKey = requireSigningKey(intentSigningKey);
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
		synchronized (intentLock) {
			String normalizedClientSubmissionId = requireCreateAllowed(clientSubmissionId, contentType, sha256, sizeBytes);
			Instant issuedAt = clock.instant();
			Instant expiresAt = issuedAt.plus(ttl);
			String uploadId = newUploadId();
			String normalizedSha256 = sha256.trim();
			String objectKey = objectKeyFor(
				normalizedClientSubmissionId,
				uploadId,
				contentType,
				normalizedSha256,
				sizeBytes,
				expiresAt
			);
			UploadIntent intent = new UploadIntent(
				uploadId,
				normalizedClientSubmissionId,
				objectKey,
				contentType,
				normalizedSha256,
				sizeBytes,
				expiresAt
			);
			intents.put(uploadId, intent);
			return new CreatedUploadIntent(uploadId, objectKey, contentType, sha256.trim(), sizeBytes, issuedAt, expiresAt);
		}
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
		synchronized (intentLock) {
			intents.values().removeIf(intent -> intent.objectKey().equals(objectKey.trim()));
		}
	}

	void discardPendingObjectKey(
		String clientSubmissionId,
		String objectKey,
		String contentType,
		String sha256,
		Long sizeBytes,
		Consumer<String> deleteObject
	) {
		if (objectKey == null || objectKey.isBlank()) {
			return;
		}
		String normalizedObjectKey = objectKey.trim();
		UploadIntent intent = pendingObjectKey(normalizedObjectKey);
		if (intent == null && !isValidSignedObjectKey(clientSubmissionId, normalizedObjectKey, contentType, sha256, sizeBytes)) {
			return;
		}
		try {
			deleteObject.accept(normalizedObjectKey);
		} catch (RuntimeException exception) {
			log.warn("Failed to discard duplicate facility report upload object {}", normalizedObjectKey, exception);
			return;
		}
		if (intent != null) {
			synchronized (intentLock) {
				intents.remove(intent.uploadId(), intent);
			}
		}
	}

	void requirePendingObjectKey(
		String clientSubmissionId,
		String objectKey,
		String contentType,
		String sha256,
		Long sizeBytes
	) {
		if (objectKey == null || objectKey.isBlank()) {
			return;
		}
		String normalizedObjectKey = objectKey.trim();
		String normalizedClientSubmissionId = requireClientSubmissionId(clientSubmissionId);
		String normalizedContentType = normalizedContentType(contentType);
		if (!isUploadContentType(normalizedContentType)) {
			throw invalidUpload();
		}
		String normalizedSha256 = requireSha256(sha256);
		long normalizedSizeBytes = requireSizeBytes(sizeBytes);
		if (!isUnclaimedObjectKey(normalizedObjectKey)) {
			throw invalidUpload();
		}
		UploadIntent localIntent = pendingObjectKey(normalizedObjectKey);
		if (localIntent != null) {
			requireMatchingIntent(
				localIntent,
				normalizedClientSubmissionId,
				normalizedContentType,
				normalizedSha256,
				normalizedSizeBytes
			);
			return;
		}
		requireSignedObjectKey(
			normalizedClientSubmissionId,
			normalizedObjectKey,
			normalizedContentType,
			normalizedSha256,
			normalizedSizeBytes
		);
	}

	static boolean isUnclaimedObjectKey(String objectKey) {
		return objectKey != null && objectKey.trim().startsWith(OBJECT_KEY_PREFIX);
	}

	void cleanupExpired(Consumer<String> deleteObject) {
		Instant now = clock.instant();
		List<UploadIntent> expiredIntents = new ArrayList<>();
		synchronized (intentLock) {
			intents.values().removeIf(intent -> {
				if (intent.expiresAt().isAfter(now)) {
					return false;
				}
				expiredIntents.add(intent);
				return true;
			});
		}
		for (UploadIntent intent : expiredIntents) {
			try {
				deleteObject.accept(intent.objectKey());
			} catch (RuntimeException exception) {
				log.warn("Failed to delete expired facility report upload object {}", intent.objectKey(), exception);
			}
		}
	}

	private String requireCreateAllowed(String clientSubmissionId, String contentType, String sha256, long sizeBytes) {
		if (clientSubmissionId == null || clientSubmissionId.isBlank()) {
			throw new InvalidFacilityReportException("신고 제출 식별자가 필요합니다.");
		}
		String normalizedClientSubmissionId = clientSubmissionId.trim();
		if (!isClientSubmissionId(normalizedClientSubmissionId)) {
			throw new InvalidFacilityReportException("신고 제출 식별자를 확인해야 합니다.");
		}
		if (!isUploadContentType(contentType)) {
			throw new InvalidFacilityReportException("사진 파일 형식을 확인해야 합니다.");
		}
		if (sha256 == null || !sha256.trim().matches("[0-9a-f]{64}")) {
			throw invalidUpload();
		}
		if (sizeBytes < 1 || sizeBytes > maxBytes) {
			throw new InvalidFacilityReportException("사진 파일 크기를 줄여야 합니다.");
		}
		if (pendingCount(normalizedClientSubmissionId) >= maxPendingCount
			|| pendingBytes(normalizedClientSubmissionId) + sizeBytes > maxPendingBytes
			|| intents.size() >= maxTotalPendingCount
			|| totalPendingBytes() + sizeBytes > maxTotalPendingBytes) {
			throw new InvalidFacilityReportException("사진 첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
		}
		return normalizedClientSubmissionId;
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

	private UploadIntent pendingObjectKey(String objectKey) {
		synchronized (intentLock) {
			return intents.values()
				.stream()
				.filter(intent -> intent.objectKey().equals(objectKey))
				.findFirst()
				.orElse(null);
		}
	}

	private boolean isValidSignedObjectKey(
		String clientSubmissionId,
		String objectKey,
		String contentType,
		String sha256,
		Long sizeBytes
	) {
		try {
			String normalizedClientSubmissionId = requireClientSubmissionId(clientSubmissionId);
			String normalizedContentType = normalizedContentType(contentType);
			if (!isUploadContentType(normalizedContentType)) {
				return false;
			}
			requireSignedObjectKey(
				normalizedClientSubmissionId,
				objectKey,
				normalizedContentType,
				requireSha256(sha256),
				requireSizeBytes(sizeBytes)
			);
			return true;
		} catch (InvalidFacilityReportException exception) {
			return false;
		}
	}

	private String objectKeyFor(
		String clientSubmissionId,
		String uploadId,
		String contentType,
		String sha256,
		long sizeBytes,
		Instant expiresAt
	) {
		long expiresEpochSecond = expiresAt.getEpochSecond();
		String signature = signature(clientSubmissionId, uploadId, contentType, sha256, sizeBytes, expiresEpochSecond);
		return OBJECT_KEY_PREFIX + expiresEpochSecond + "/" + uploadId + "/" + signature + extensionFor(contentType);
	}

	private void requireMatchingIntent(
		UploadIntent intent,
		String clientSubmissionId,
		String contentType,
		String sha256,
		long sizeBytes
	) {
		if (intent.expiresAt().isBefore(clock.instant())
			|| !intent.clientSubmissionId().equals(clientSubmissionId)
			|| !intent.contentType().equals(contentType)
			|| !intent.sha256().equals(sha256)
			|| intent.sizeBytes() != sizeBytes) {
			throw invalidUpload();
		}
	}

	private void requireSignedObjectKey(
		String clientSubmissionId,
		String objectKey,
		String contentType,
		String sha256,
		long sizeBytes
	) {
		SignedObjectKey signedObjectKey = parseSignedObjectKey(objectKey, contentType);
		if (signedObjectKey.expiresAt().isBefore(clock.instant())) {
			throw invalidUpload();
		}
		String expectedSignature = signature(
			clientSubmissionId,
			signedObjectKey.uploadId(),
			contentType,
			sha256,
			sizeBytes,
			signedObjectKey.expiresAt().getEpochSecond()
		);
		if (!MessageDigest.isEqual(
			expectedSignature.getBytes(StandardCharsets.US_ASCII),
			signedObjectKey.signature().getBytes(StandardCharsets.US_ASCII)
		)) {
			throw invalidUpload();
		}
	}

	private SignedObjectKey parseSignedObjectKey(String objectKey, String contentType) {
		if (!objectKey.startsWith(OBJECT_KEY_PREFIX)) {
			throw invalidUpload();
		}
		String suffix = objectKey.substring(OBJECT_KEY_PREFIX.length());
		String[] parts = suffix.split("/", 3);
		if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
			throw invalidUpload();
		}
		String expectedExtension = extensionFor(contentType);
		String signedFileName = parts[2];
		if (!signedFileName.endsWith(expectedExtension)) {
			throw invalidUpload();
		}
		String signature = signedFileName.substring(0, signedFileName.length() - expectedExtension.length());
		if (!signature.matches("[0-9a-f]{64}")) {
			throw invalidUpload();
		}
		try {
			return new SignedObjectKey(parts[1], Instant.ofEpochSecond(Long.parseLong(parts[0])), signature);
		} catch (NumberFormatException exception) {
			throw invalidUpload();
		}
	}

	private String signature(
		String clientSubmissionId,
		String uploadId,
		String contentType,
		String sha256,
		long sizeBytes,
		long expiresEpochSecond
	) {
		String payload = String.join(
			"\n",
			clientSubmissionId,
			uploadId,
			contentType,
			sha256,
			Long.toString(sizeBytes),
			Long.toString(expiresEpochSecond)
		);
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(
				("facility-report-upload-intent:" + intentSigningKey).getBytes(StandardCharsets.UTF_8),
				"HmacSHA256"
			));
			return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("HmacSHA256 algorithm is unavailable", exception);
		}
	}

	private String requireSigningKey(String signingKey) {
		if (signingKey == null || signingKey.isBlank()) {
			throw new IllegalStateException("upload intent signing key is required");
		}
		return signingKey.trim();
	}

	private String requireClientSubmissionId(String clientSubmissionId) {
		if (clientSubmissionId == null || clientSubmissionId.isBlank()) {
			throw invalidUpload();
		}
		String normalizedClientSubmissionId = clientSubmissionId.trim();
		if (!isClientSubmissionId(normalizedClientSubmissionId)) {
			throw invalidUpload();
		}
		return normalizedClientSubmissionId;
	}

	private String requireSha256(String sha256) {
		if (sha256 == null || !sha256.trim().matches("[0-9a-f]{64}")) {
			throw invalidUpload();
		}
		return sha256.trim();
	}

	private long requireSizeBytes(Long sizeBytes) {
		if (sizeBytes == null || sizeBytes < 1 || sizeBytes > maxBytes) {
			throw invalidUpload();
		}
		return sizeBytes;
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

	private boolean isUploadContentType(String contentType) {
		return "image/jpeg".equals(contentType) || "image/png".equals(contentType) || "image/webp".equals(contentType);
	}

	private boolean isClientSubmissionId(String clientSubmissionId) {
		return clientSubmissionId.length() <= 120 && clientSubmissionId.matches("[A-Za-z0-9_-]+");
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

	private record SignedObjectKey(
		String uploadId,
		Instant expiresAt,
		String signature
	) {
	}
}
