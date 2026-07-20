package com.easysubway.admin.identity.application.service;

import com.easysubway.admin.authorization.AdminAuthorization;
import com.easysubway.admin.authorization.AdminRbacRole;
import com.easysubway.admin.authorization.application.port.out.AdminRbacAuthorityRepository;
import com.easysubway.admin.identity.application.port.out.AdminIdentityRepository;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityRole;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

public class AdminIdentityUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

	// RBAC 권한이 배선되지 않은 소비 경로(3-인자 편의 생성자)를 위한 명시적 no-op 구현.
	// port가 단일 조회 함수가 아니라 seed/revoke 쓰기까지 포함하므로 람다로 대체할 수 없다.
	private static final AdminRbacAuthorityRepository NO_ADMIN_RBAC_AUTHORITIES =
		new AdminRbacAuthorityRepository() {
			@Override
			public Set<String> findPermissionAuthorities(String loginId) {
				return Set.of();
			}

			@Override
			public void seedRole(String loginId, AdminRbacRole role) {
			}

			@Override
			public void revokeStaleBootstrapRoles(Set<String> activeBootstrapLoginIds) {
			}
		};

	private final AdminIdentityRepository adminIdentityRepository;
	private final AdminRbacAuthorityRepository adminRbacAuthorityRepository;
	private final UserDetailsService fallbackUserDetailsService;
	private final Clock clock;

	public AdminIdentityUserDetailsService(
		AdminIdentityRepository adminIdentityRepository,
		UserDetailsService fallbackUserDetailsService,
		Clock clock
	) {
		this(adminIdentityRepository, NO_ADMIN_RBAC_AUTHORITIES, fallbackUserDetailsService, clock);
	}

	public AdminIdentityUserDetailsService(
		AdminIdentityRepository adminIdentityRepository,
		AdminRbacAuthorityRepository adminRbacAuthorityRepository,
		UserDetailsService fallbackUserDetailsService,
		Clock clock
	) {
		this.adminIdentityRepository = adminIdentityRepository;
		this.adminRbacAuthorityRepository = adminRbacAuthorityRepository;
		this.fallbackUserDetailsService = fallbackUserDetailsService;
		this.clock = clock;
	}

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		return adminIdentityRepository.findByLoginId(username)
			.map(this::toUserDetails)
			.orElseGet(() -> fallbackUserDetailsService.loadUserByUsername(username));
	}

	@Override
	public UserDetails updatePassword(UserDetails user, String newPassword) {
		throw new UnsupportedOperationException("관리자 비밀번호 변경은 identity 저장소 workflow로 처리합니다.");
	}

	private UserDetails toUserDetails(AdminIdentity identity) {
		LocalDateTime now = LocalDateTime.now(clock);
		var authorities = new ArrayList<String>();
		authorities.add("ROLE_" + identity.role().name().toUpperCase(Locale.ROOT));
		var assignedAuthorities = adminRbacAuthorityRepository.findPermissionAuthorities(identity.loginId());
		authorities.addAll(AdminAuthorization.authoritiesFor(
			identity.role(),
			assignedAuthorities
		));
		if (identity.role() == AdminIdentityRole.ADMIN && identity.bootstrapManaged() && assignedAuthorities.isEmpty()) {
			authorities.addAll(AdminAuthorization.superAdminAuthorities());
		}
		return User.withUsername(identity.loginId())
			.password(identity.passwordHash())
			.authorities(authorities.toArray(String[]::new))
			.disabled(identity.disabled())
			.accountLocked(identity.lockedAt(now))
			.accountExpired(false)
			.credentialsExpired(identity.credentialsExpiredAt(now))
			.build();
	}
}
