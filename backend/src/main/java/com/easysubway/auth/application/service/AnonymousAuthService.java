package com.easysubway.auth.application.service;

import com.easysubway.auth.application.port.in.AnonymousAuthUseCase;
import com.easysubway.auth.application.port.out.AnonymousAuthTokenPort;
import com.easysubway.auth.application.port.out.RegisterAnonymousUserPort;
import com.easysubway.auth.domain.AnonymousAuthTokenSession;
import com.easysubway.auth.domain.AnonymousUserCredentials;
import com.easysubway.auth.domain.AuthenticatedUser;
import com.easysubway.auth.domain.InvalidAnonymousAuthException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnonymousAuthService implements AnonymousAuthUseCase {

	private static final int MAX_USER_ID_GENERATION_ATTEMPTS = 10;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final RegisterAnonymousUserPort registerAnonymousUserPort;
	private final AnonymousAuthTokenPort anonymousAuthTokenPort;
	private final Clock clock;
	private final Supplier<String> userIdGenerator;
	private final Supplier<String> passwordGenerator;
	private final Supplier<String> accessTokenGenerator;
	private final Supplier<String> refreshTokenGenerator;

	@Autowired
	public AnonymousAuthService(
		RegisterAnonymousUserPort registerAnonymousUserPort,
		AnonymousAuthTokenPort anonymousAuthTokenPort
	) {
		this(
			registerAnonymousUserPort,
			anonymousAuthTokenPort,
			Clock.systemDefaultZone(),
			AnonymousAuthService::newAnonymousUserId,
			AnonymousAuthService::newAnonymousPassword,
			AnonymousAuthService::newAnonymousToken,
			AnonymousAuthService::newAnonymousToken
		);
	}

	public AnonymousAuthService(
		RegisterAnonymousUserPort registerAnonymousUserPort,
		AnonymousAuthTokenPort anonymousAuthTokenPort,
		Clock clock,
		Supplier<String> userIdGenerator,
		Supplier<String> passwordGenerator,
		Supplier<String> accessTokenGenerator,
		Supplier<String> refreshTokenGenerator
	) {
		this.registerAnonymousUserPort = registerAnonymousUserPort;
		this.anonymousAuthTokenPort = anonymousAuthTokenPort;
		this.clock = clock;
		this.userIdGenerator = userIdGenerator;
		this.passwordGenerator = passwordGenerator;
		this.accessTokenGenerator = accessTokenGenerator;
		this.refreshTokenGenerator = refreshTokenGenerator;
	}

	@Override
	@Transactional
	public AnonymousAuthTokenSession issueAnonymousUser() {
		for (int attempt = 0; attempt < MAX_USER_ID_GENERATION_ATTEMPTS; attempt++) {
			String userId = userIdGenerator.get();
			if (registerAnonymousUserPort.existsByUserId(userId)) {
				continue;
			}

			var credentials = new AnonymousUserCredentials(
				userId,
				passwordGenerator.get(),
				LocalDateTime.now(clock)
			);
			registerAnonymousUserPort.registerAnonymousUser(credentials);
			String accessToken = accessTokenGenerator.get();
			String refreshToken = refreshTokenGenerator.get();
			anonymousAuthTokenPort.saveIssuedTokenHashes(
				userId,
				AnonymousAuthTokenHasher.sha256(accessToken),
				AnonymousAuthTokenHasher.sha256(refreshToken),
				credentials.createdAt()
			);
			return new AnonymousAuthTokenSession(userId, accessToken, refreshToken, credentials.createdAt());
		}

		throw new InvalidAnonymousAuthException("익명 사용자 식별자를 만들 수 없습니다.");
	}

	@Override
	@Transactional(noRollbackFor = InvalidAnonymousAuthException.class)
	public AnonymousAuthTokenSession refreshAnonymousUser(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new InvalidAnonymousAuthException("익명 인증 세션을 갱신할 수 없습니다.");
		}
		LocalDateTime refreshedAt = LocalDateTime.now(clock);
		String refreshTokenHash = AnonymousAuthTokenHasher.sha256(refreshToken.trim());
		String userId = anonymousAuthTokenPort.consumeRefreshTokenHash(refreshTokenHash, refreshedAt)
			.orElseThrow(() -> {
				anonymousAuthTokenPort.saveAuditEvent("REFRESH_TOKEN_REUSED_OR_INVALID", null, refreshedAt);
				return new InvalidAnonymousAuthException("익명 인증 세션을 갱신할 수 없습니다.");
			});

		String nextAccessToken = accessTokenGenerator.get();
		String nextRefreshToken = refreshTokenGenerator.get();
		anonymousAuthTokenPort.saveIssuedTokenHashes(
			userId,
			AnonymousAuthTokenHasher.sha256(nextAccessToken),
			AnonymousAuthTokenHasher.sha256(nextRefreshToken),
			refreshedAt
		);
		return new AnonymousAuthTokenSession(userId, nextAccessToken, nextRefreshToken, refreshedAt);
	}

	@Override
	public AuthenticatedUser currentUser(String userId, String authType) {
		return new AuthenticatedUser(userId, authType, registerAnonymousUserPort.isAnonymousUser(userId));
	}

	private static String newAnonymousUserId() {
		return "anonymous-" + UUID.randomUUID();
	}

	private static String newAnonymousPassword() {
		return newAnonymousToken();
	}

	private static String newAnonymousToken() {
		byte[] bytes = new byte[24];
		SECURE_RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
