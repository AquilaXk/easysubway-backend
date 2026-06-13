package com.easysubway.common.security;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.util.Assert;

public class ConcurrentUserDetailsManager implements UserDetailsManager, UserDetailsPasswordService {

	private final ConcurrentMap<String, UserDetails> usersByUsername = new ConcurrentHashMap<>();

	@Override
	public void createUser(UserDetails user) {
		Assert.isTrue(
			usersByUsername.putIfAbsent(normalizedUsername(user.getUsername()), copyOf(user)) == null,
			"user should not exist"
		);
	}

	@Override
	public void updateUser(UserDetails user) {
		Assert.isTrue(
			usersByUsername.replace(normalizedUsername(user.getUsername()), copyOf(user)) != null,
			"user should exist"
		);
	}

	@Override
	public void deleteUser(String username) {
		usersByUsername.remove(normalizedUsername(username));
	}

	@Override
	public void changePassword(String oldPassword, String newPassword) {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			throw new AccessDeniedException(
				"Can't change password as no Authentication object found in context for current user."
			);
		}
		updatePassword(loadUserByUsername(authentication.getName()), newPassword);
	}

	@Override
	public boolean userExists(String username) {
		return usersByUsername.containsKey(normalizedUsername(username));
	}

	@Override
	public UserDetails updatePassword(UserDetails user, String newPassword) {
		String username = user.getUsername();
		UserDetails updatedUser = copyWithPassword(user, newPassword);
		if (usersByUsername.replace(normalizedUsername(username), updatedUser) == null) {
			throw usernameNotFound(username);
		}
		return copyOf(updatedUser);
	}

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		UserDetails user = usersByUsername.get(normalizedUsername(username));
		if (user == null) {
			throw usernameNotFound(username);
		}
		return copyOf(user);
	}

	private UserDetails copyOf(UserDetails user) {
		return User.withUsername(user.getUsername())
			.password(user.getPassword())
			.disabled(!user.isEnabled())
			.accountExpired(!user.isAccountNonExpired())
			.credentialsExpired(!user.isCredentialsNonExpired())
			.accountLocked(!user.isAccountNonLocked())
			.authorities(user.getAuthorities())
			.build();
	}

	private UserDetails copyWithPassword(UserDetails user, String newPassword) {
		return User.withUsername(user.getUsername())
			.password(newPassword)
			.disabled(!user.isEnabled())
			.accountExpired(!user.isAccountNonExpired())
			.credentialsExpired(!user.isCredentialsNonExpired())
			.accountLocked(!user.isAccountNonLocked())
			.authorities(user.getAuthorities())
			.build();
	}

	private String normalizedUsername(String username) {
		return username.toLowerCase(Locale.ROOT);
	}

	private UsernameNotFoundException usernameNotFound(String username) {
		return new UsernameNotFoundException("user '" + username + "' not found");
	}
}
