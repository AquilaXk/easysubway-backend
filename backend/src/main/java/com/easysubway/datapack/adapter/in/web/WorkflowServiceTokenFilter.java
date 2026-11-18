package com.easysubway.datapack.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 워크플로 전용 서비스 토큰(Bearer) 인증 필터. 토큰 미설정 시 어떤 요청도 인증하지 않아
 * 체인이 전면 거부한다(자동화 dormant = 안전 기본값). admin 세션과 완전 분리.
 */
public class WorkflowServiceTokenFilter extends OncePerRequestFilter {

	private static final RequestMatcher AUTOMATION_STATUS_MATCHER =
		PathPatternRequestMatcher.withDefaults().matcher("/admin/api/datapack/automation-status");
	private static final String WORKFLOW_ROLE = "ROLE_DATAPACK_WORKFLOW";
	private static final String AUTOMATION_STATUS_ROLE = "ROLE_DATAPACK_AUTOMATION_STATUS";

	private final String workflowToken;
	private final String automationStatusToken;

	public WorkflowServiceTokenFilter(String workflowToken, String automationStatusToken) {
		this.workflowToken = workflowToken;
		this.automationStatusToken = automationStatusToken;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		boolean automationStatusRequest = AUTOMATION_STATUS_MATCHER.matches(request);
		String expectedToken = automationStatusRequest ? automationStatusToken : workflowToken;
		String role = automationStatusRequest ? AUTOMATION_STATUS_ROLE : WORKFLOW_ROLE;
		String header = request.getHeader("Authorization");
		if (expectedToken != null && !expectedToken.isBlank()
			&& header != null && header.startsWith("Bearer ")
			&& constantTimeEquals(header.substring(7), expectedToken)) {
			var auth = new UsernamePasswordAuthenticationToken(
				"datapack-workflow", null, List.of(new SimpleGrantedAuthority(role)));
			SecurityContextHolder.getContext().setAuthentication(auth);
		}
		chain.doFilter(request, response);
	}

	private static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
