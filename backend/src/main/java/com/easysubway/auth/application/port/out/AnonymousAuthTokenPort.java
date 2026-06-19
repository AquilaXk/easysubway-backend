package com.easysubway.auth.application.port.out;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AnonymousAuthTokenPort {

	void saveIssuedTokenHashes(
		String userId,
		String accessTokenHash,
		String refreshTokenHash,
		LocalDateTime issuedAt
	);

	Optional<String> findUserIdByAccessTokenHash(String accessTokenHash);

	Optional<String> consumeRefreshTokenHash(String refreshTokenHash, LocalDateTime consumedAt);

	void saveAuditEvent(String eventType, String userId, LocalDateTime occurredAt);

	void deleteTokenHashesByUserId(String userId);
}
