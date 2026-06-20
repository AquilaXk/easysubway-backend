package com.easysubway.report.adapter.in.web;

import com.easysubway.report.domain.InvalidFacilityReportException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

interface FacilityReportUploadUrlSigner {

	SignedUploadUrl sign(FacilityReportUploadIntents.CreatedUploadIntent intent);

	record SignedUploadUrl(String uploadUrl, String uploadMethod) {
	}
}

@Component
@Profile("!prod")
class LocalFacilityReportUploadUrlSigner implements FacilityReportUploadUrlSigner {

	@Override
	public SignedUploadUrl sign(FacilityReportUploadIntents.CreatedUploadIntent intent) {
		return new SignedUploadUrl("/api/v1/report-uploads/" + intent.uploadId(), "PUT");
	}
}

@Component
@Profile("prod")
class ObjectStorageFacilityReportUploadUrlSigner implements FacilityReportUploadUrlSigner {

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private final String endpoint;
	private final String bucket;
	private final String secretKey;

	ObjectStorageFacilityReportUploadUrlSigner(
		@Value("${easysubway.report.upload.object-storage-endpoint:}") String endpoint,
		@Value("${easysubway.report.upload.bucket:}") String bucket,
		@Value("${easysubway.report.upload.object-storage-secret-key:}") String secretKey
	) {
		this.endpoint = requireText(endpoint, "운영 object storage endpoint 설정이 필요합니다.");
		this.bucket = requireText(bucket, "운영 report upload bucket 설정이 필요합니다.");
		this.secretKey = requireText(secretKey, "운영 object storage secret 설정이 필요합니다.");
	}

	@Override
	public SignedUploadUrl sign(FacilityReportUploadIntents.CreatedUploadIntent intent) {
		String objectKey = urlEncode(intent.objectKey());
		String expiresAt = urlEncode(intent.expiresAt().toString());
		String signature = signature(intent.objectKey(), intent.expiresAt());
		String uploadUrl = "%s/%s/%s?expiresAt=%s&signature=%s".formatted(
			endpoint.replaceAll("/+$", ""),
			urlEncode(bucket),
			objectKey,
			expiresAt,
			signature
		);
		return new SignedUploadUrl(uploadUrl, "PUT");
	}

	private String signature(String objectKey, Instant expiresAt) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return HexFormat.of().formatHex(mac.doFinal("%s:%s".formatted(objectKey, expiresAt).getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA256 is required", exception);
		}
	}

	private static String requireText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new InvalidFacilityReportException(message);
		}
		return value.trim();
	}

	private static String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
