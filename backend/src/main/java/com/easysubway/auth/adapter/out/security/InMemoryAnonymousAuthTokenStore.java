package com.easysubway.auth.adapter.out.security;

import com.easysubway.auth.application.port.out.AnonymousAuthTokenPort;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryAnonymousAuthTokenStore implements AnonymousAuthTokenPort {

	private final Map<String, String> userIdsByAccessTokenHash = new ConcurrentHashMap<>();
	private final Map<String, String> accessTokenHashesByRefreshTokenHash = new ConcurrentHashMap<>();
	private final List<String> auditEvents = new CopyOnWriteArrayList<>();

	@Override
	public void saveIssuedTokenHashes(
		String userId,
		String accessTokenHash,
		String refreshTokenHash,
		LocalDateTime issuedAt
	) {
		userIdsByAccessTokenHash.put(accessTokenHash, userId);
		accessTokenHashesByRefreshTokenHash.put(refreshTokenHash, accessTokenHash);
	}

	@Override
	public Optional<String> findUserIdByAccessTokenHash(String accessTokenHash) {
		return Optional.ofNullable(userIdsByAccessTokenHash.get(accessTokenHash));
	}

	@Override
	public Optional<String> consumeRefreshTokenHash(String refreshTokenHash, LocalDateTime consumedAt) {
		String accessTokenHash = accessTokenHashesByRefreshTokenHash.remove(refreshTokenHash);
		if (accessTokenHash == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(userIdsByAccessTokenHash.get(accessTokenHash));
	}

	@Override
	public void saveAuditEvent(String eventType, String userId, LocalDateTime occurredAt) {
		auditEvents.add(eventType);
	}

	@Override
	public void deleteTokenHashesByUserId(String userId) {
		userIdsByAccessTokenHash.entrySet().removeIf(entry -> entry.getValue().equals(userId));
		accessTokenHashesByRefreshTokenHash.values().removeIf(accessTokenHash ->
			!userIdsByAccessTokenHash.containsKey(accessTokenHash)
		);
	}
}
