package com.easysubway.auth.adapter.in.web;

import com.easysubway.auth.application.port.in.AnonymousAuthRateLimitUseCase;
import com.easysubway.auth.application.port.in.AnonymousAuthUseCase;
import com.easysubway.auth.adapter.out.security.AnonymousBearerPrincipal;
import com.easysubway.auth.domain.AnonymousAuthTokenSession;
import com.easysubway.auth.domain.AnonymousAuthRateLimitExceededException;
import com.easysubway.auth.domain.AuthenticatedUser;
import com.easysubway.auth.domain.InvalidAnonymousAuthException;
import com.easysubway.common.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AnonymousAuthController {

	private final AnonymousAuthUseCase anonymousAuthUseCase;
	private final AnonymousAuthRateLimitUseCase anonymousAuthRateLimitUseCase;
	private final AnonymousAuthClientIpResolver anonymousAuthClientIpResolver;

	AnonymousAuthController(
		AnonymousAuthUseCase anonymousAuthUseCase,
		AnonymousAuthRateLimitUseCase anonymousAuthRateLimitUseCase,
		AnonymousAuthClientIpResolver anonymousAuthClientIpResolver
	) {
		this.anonymousAuthUseCase = anonymousAuthUseCase;
		this.anonymousAuthRateLimitUseCase = anonymousAuthRateLimitUseCase;
		this.anonymousAuthClientIpResolver = anonymousAuthClientIpResolver;
	}

	@PostMapping("/api/v1/auth/anonymous")
	ApiResponse<AnonymousAuthResponse> issueAnonymousUser(HttpServletRequest request) {
		anonymousAuthRateLimitUseCase.check(clientKeyFrom(request));
		return ApiResponse.ok(AnonymousAuthResponse.from(anonymousAuthUseCase.issueAnonymousUser()));
	}

	@PostMapping("/api/v1/auth/anonymous/refresh")
	ApiResponse<AnonymousAuthResponse> refreshAnonymousUser(@Valid @RequestBody AnonymousAuthRefreshRequest request) {
		return ApiResponse.ok(AnonymousAuthResponse.from(anonymousAuthUseCase.refreshAnonymousUser(request.refreshToken())));
	}

	@GetMapping("/api/v1/me")
	ApiResponse<AuthenticatedUserResponse> currentUser(Authentication authentication) {
		return ApiResponse.ok(AuthenticatedUserResponse.from(anonymousAuthUseCase.currentUser(
			userIdFrom(authentication),
			authTypeFrom(authentication)
		)));
	}

	@ExceptionHandler(AnonymousAuthRateLimitExceededException.class)
	@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
	ApiResponse<Void> handleRateLimitExceeded(AnonymousAuthRateLimitExceededException exception) {
		return ApiResponse.fail(exception.getMessage());
	}

	@ExceptionHandler(InvalidAnonymousAuthException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	ApiResponse<Void> handleInvalidAnonymousAuth(InvalidAnonymousAuthException exception) {
		return ApiResponse.fail(exception.getMessage());
	}

	private String clientKeyFrom(HttpServletRequest request) {
		return anonymousAuthClientIpResolver.resolve(request);
	}

	private String userIdFrom(Authentication authentication) {
		if (authentication.getPrincipal() instanceof AnonymousBearerPrincipal principal) {
			return principal.getName();
		}
		return authentication.getName();
	}

	private String authTypeFrom(Authentication authentication) {
		return authentication.getPrincipal() instanceof AnonymousBearerPrincipal ? "BEARER" : "BASIC";
	}

	record AnonymousAuthResponse(
		String userId,
		String accessToken,
		String refreshToken,
		String authType,
		boolean anonymous,
		LocalDateTime createdAt
	) {

		static AnonymousAuthResponse from(AnonymousAuthTokenSession session) {
			return new AnonymousAuthResponse(
				session.userId(),
				session.accessToken(),
				session.refreshToken(),
				"BEARER",
				true,
				session.createdAt()
			);
		}
	}

	record AnonymousAuthRefreshRequest(@NotBlank String refreshToken) {
	}

	record AuthenticatedUserResponse(
		String userId,
		String authType,
		boolean anonymous
	) {

		static AuthenticatedUserResponse from(AuthenticatedUser user) {
			return new AuthenticatedUserResponse(
				user.userId(),
				user.authType(),
				user.anonymous()
			);
		}
	}
}
