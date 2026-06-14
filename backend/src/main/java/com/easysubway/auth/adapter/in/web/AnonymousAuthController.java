package com.easysubway.auth.adapter.in.web;

import com.easysubway.auth.application.port.in.AnonymousAuthRateLimitUseCase;
import com.easysubway.auth.application.port.in.AnonymousAuthUseCase;
import com.easysubway.auth.domain.AnonymousAuthRateLimitExceededException;
import com.easysubway.auth.domain.AnonymousUserCredentials;
import com.easysubway.auth.domain.AuthenticatedUser;
import com.easysubway.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AnonymousAuthController {

	private final AnonymousAuthUseCase anonymousAuthUseCase;
	private final AnonymousAuthRateLimitUseCase anonymousAuthRateLimitUseCase;

	AnonymousAuthController(
		AnonymousAuthUseCase anonymousAuthUseCase,
		AnonymousAuthRateLimitUseCase anonymousAuthRateLimitUseCase
	) {
		this.anonymousAuthUseCase = anonymousAuthUseCase;
		this.anonymousAuthRateLimitUseCase = anonymousAuthRateLimitUseCase;
	}

	@PostMapping("/api/v1/auth/anonymous")
	ApiResponse<AnonymousAuthResponse> issueAnonymousUser(HttpServletRequest request) {
		anonymousAuthRateLimitUseCase.check(clientKeyFrom(request));
		return ApiResponse.ok(AnonymousAuthResponse.from(anonymousAuthUseCase.issueAnonymousUser()));
	}

	@GetMapping("/api/v1/me")
	ApiResponse<AuthenticatedUserResponse> currentUser(Principal principal) {
		return ApiResponse.ok(AuthenticatedUserResponse.from(anonymousAuthUseCase.currentUser(principal.getName())));
	}

	@ExceptionHandler(AnonymousAuthRateLimitExceededException.class)
	@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
	ApiResponse<Void> handleRateLimitExceeded(AnonymousAuthRateLimitExceededException exception) {
		return ApiResponse.fail(exception.getMessage());
	}

	private String clientKeyFrom(HttpServletRequest request) {
		// 신뢰 프록시 설정 전에는 조작 가능한 전달 헤더 대신 서블릿 원격 주소로 발급 남용을 제한한다.
		return request.getRemoteAddr();
	}

	record AnonymousAuthResponse(
		String userId,
		String password,
		String authType,
		boolean anonymous,
		LocalDateTime createdAt
	) {

		static AnonymousAuthResponse from(AnonymousUserCredentials credentials) {
			return new AnonymousAuthResponse(
				credentials.userId(),
				credentials.password(),
				"BASIC",
				true,
				credentials.createdAt()
			);
		}
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
