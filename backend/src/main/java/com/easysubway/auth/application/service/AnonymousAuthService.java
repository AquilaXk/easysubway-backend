package com.easysubway.auth.application.service;

import com.easysubway.auth.application.port.in.AnonymousAuthUseCase;
import com.easysubway.auth.application.port.out.RegisterAnonymousUserPort;
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

@Service
public class AnonymousAuthService implements AnonymousAuthUseCase {

	private static final int MAX_USER_ID_GENERATION_ATTEMPTS = 10;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final RegisterAnonymousUserPort registerAnonymousUserPort;
	private final Clock clock;
	private final Supplier<String> userIdGenerator;
	private final Supplier<String> passwordGenerator;

	@Autowired
	public AnonymousAuthService(RegisterAnonymousUserPort registerAnonymousUserPort) {
		this(
			registerAnonymousUserPort,
			Clock.systemDefaultZone(),
			AnonymousAuthService::newAnonymousUserId,
			AnonymousAuthService::newAnonymousPassword
		);
	}

	public AnonymousAuthService(
		RegisterAnonymousUserPort registerAnonymousUserPort,
		Clock clock,
		Supplier<String> userIdGenerator,
		Supplier<String> passwordGenerator
	) {
		this.registerAnonymousUserPort = registerAnonymousUserPort;
		this.clock = clock;
		this.userIdGenerator = userIdGenerator;
		this.passwordGenerator = passwordGenerator;
	}

	@Override
	public AnonymousUserCredentials issueAnonymousUser() {
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
			return credentials;
		}

		throw new InvalidAnonymousAuthException("익명 사용자 식별자를 만들 수 없습니다.");
	}

	@Override
	public AuthenticatedUser currentUser(String userId) {
		return new AuthenticatedUser(userId, "BASIC", registerAnonymousUserPort.isAnonymousUser(userId));
	}

	private static String newAnonymousUserId() {
		return "anonymous-" + UUID.randomUUID();
	}

	private static String newAnonymousPassword() {
		byte[] bytes = new byte[24];
		SECURE_RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
