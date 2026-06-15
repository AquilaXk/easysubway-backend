package com.easysubway.auth.adapter.out.security;

import com.easysubway.auth.application.port.out.RegisterAnonymousUserPort;
import com.easysubway.auth.domain.AnonymousUserCredentials;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Component;

@Component
public class SpringSecurityAnonymousUserRegistry implements RegisterAnonymousUserPort {

	static final int MAX_ANONYMOUS_USERS = 10_000;

	private final UserDetailsManager userDetailsManager;
	private final PasswordEncoder passwordEncoder;
	private final int maxAnonymousUsers;
	private final Deque<String> issuedAnonymousUserIds = new ArrayDeque<>();
	private final Set<String> issuedAnonymousUserIdSet = new HashSet<>();

	@Autowired
	public SpringSecurityAnonymousUserRegistry(
		UserDetailsManager userDetailsManager,
		PasswordEncoder passwordEncoder
	) {
		this(userDetailsManager, passwordEncoder, MAX_ANONYMOUS_USERS);
	}

	SpringSecurityAnonymousUserRegistry(
		UserDetailsManager userDetailsManager,
		PasswordEncoder passwordEncoder,
		int maxAnonymousUsers
	) {
		this.userDetailsManager = userDetailsManager;
		this.passwordEncoder = passwordEncoder;
		this.maxAnonymousUsers = maxAnonymousUsers;
	}

	@Override
	public synchronized boolean existsByUserId(String userId) {
		return userDetailsManager.userExists(userId);
	}

	@Override
	public synchronized boolean isAnonymousUser(String userId) {
		return issuedAnonymousUserIdSet.contains(userId);
	}

	@Override
	public synchronized void registerAnonymousUser(AnonymousUserCredentials credentials) {
		// Spring Security에는 인코딩된 비밀번호만 등록하고, 평문은 발급 응답에서만 1회 노출한다.
		userDetailsManager.createUser(User.withUsername(credentials.userId())
			.password(passwordEncoder.encode(credentials.password()))
			.roles("USER")
			.build());
		issuedAnonymousUserIds.addLast(credentials.userId());
		issuedAnonymousUserIdSet.add(credentials.userId());
		evictOldestAnonymousUsers();
	}

	@Override
	public synchronized boolean deleteAnonymousUser(String userId) {
		if (!issuedAnonymousUserIdSet.contains(userId)) {
			return false;
		}
		if (userDetailsManager.userExists(userId)) {
			userDetailsManager.deleteUser(userId);
		}
		issuedAnonymousUserIdSet.remove(userId);
		issuedAnonymousUserIds.remove(userId);
		return true;
	}

	private void evictOldestAnonymousUsers() {
		// 공개 발급 API가 프로세스 메모리에 익명 계정을 무한히 쌓지 않게 런타임 발급분만 정리한다.
		while (issuedAnonymousUserIds.size() > maxAnonymousUsers) {
			String oldestUserId = issuedAnonymousUserIds.removeFirst();
			issuedAnonymousUserIdSet.remove(oldestUserId);
			if (userDetailsManager.userExists(oldestUserId)) {
				userDetailsManager.deleteUser(oldestUserId);
			}
		}
	}
}
