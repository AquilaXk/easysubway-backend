package com.easysubway.admin.audit.application.service;

import com.easysubway.admin.audit.application.port.out.AdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditWriter {

	private final AdminAuditEventRepository auditEventRepository;
	private final Clock clock;
	private final boolean enabled;

	@Autowired
	public AdminAuditWriter(AdminAuditEventRepository auditEventRepository) {
		this(auditEventRepository, Clock.systemUTC(), true);
	}

	private AdminAuditWriter(AdminAuditEventRepository auditEventRepository, Clock clock, boolean enabled) {
		this.auditEventRepository = auditEventRepository;
		this.clock = clock;
		this.enabled = enabled;
	}

	public static AdminAuditWriter noop() {
		return new AdminAuditWriter(null, Clock.systemUTC(), false);
	}

	public void privacyRead(
		Authentication authentication,
		HttpServletRequest request,
		String targetType,
		String targetId,
		String action,
		String reason
	) {
		writeAudit(
			authentication,
			request,
			AdminAuditEventType.PRIVACY_READ,
			targetType,
			targetId,
			action,
			AdminAuditOutcome.SUCCESS,
			reason
		);
	}

	public void batchOperation(
		Authentication authentication,
		HttpServletRequest request,
		String targetType,
		String targetId,
		String action,
		AdminAuditOutcome outcome,
		String reason
	) {
		writeAudit(
			authentication,
			request,
			AdminAuditEventType.BATCH_OPERATION,
			targetType,
			targetId,
			action,
			outcome,
			reason
		);
	}

	private void writeAudit(
		Authentication authentication,
		HttpServletRequest request,
		AdminAuditEventType eventType,
		String targetType,
		String targetId,
		String action,
		AdminAuditOutcome outcome,
		String reason
	) {
		if (!enabled || !isAuthenticated(authentication)) {
			return;
		}
		auditEventRepository.save(new AdminAuditEvent(
			null,
			eventType,
			authentication.getName(),
			authorities(authentication),
			correlationId(request),
			clientIp(request),
			userAgent(request),
			targetType,
			targetId,
			action,
			outcome,
			reason,
			LocalDateTime.now(clock)
		));
	}

	public String sha256TargetId(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
		}
	}

	private static boolean isAuthenticated(Authentication authentication) {
		return authentication != null
			&& authentication.isAuthenticated()
			&& !(authentication instanceof AnonymousAuthenticationToken);
	}

	private static String authorities(Authentication authentication) {
		return authentication.getAuthorities()
			.stream()
			.map(authority -> authority.getAuthority())
			.sorted(Comparator.naturalOrder())
			.collect(Collectors.joining(","));
	}

	private static String correlationId(HttpServletRequest request) {
		String value = request.getHeader("X-Correlation-Id");
		if (value == null || value.isBlank()) {
			return "missing";
		}
		String trimmed = value.trim();
		return trimmed.matches("[A-Za-z0-9._-]{1,64}") ? trimmed : "invalid";
	}

	private static String clientIp(HttpServletRequest request) {
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			return forwardedFor.split(",", 2)[0].trim();
		}
		return request.getRemoteAddr();
	}

	private static String userAgent(HttpServletRequest request) {
		String value = request.getHeader("User-Agent");
		if (value == null || value.isBlank()) {
			return "missing";
		}
		return value.length() > 300 ? value.substring(0, 300) : value;
	}
}
