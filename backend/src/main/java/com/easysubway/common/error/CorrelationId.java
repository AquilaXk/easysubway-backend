package com.easysubway.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

public final class CorrelationId {

	public static final String HEADER = "X-Correlation-Id";
	public static final String ATTRIBUTE = CorrelationId.class.getName();
	public static final String MDC_KEY = "correlationId";
	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private CorrelationId() {
	}

	public static String create() {
		return UUID.randomUUID().toString();
	}

	public static boolean isValid(String value) {
		return value != null && VALID.matcher(value).matches();
	}

	public static String currentOrCreate(HttpServletRequest request) {
		Object existing = request.getAttribute(ATTRIBUTE);
		if (existing instanceof String value && isValid(value)) {
			return value;
		}
		String fromHeader = request.getHeader(HEADER);
		if (fromHeader != null) {
			String trimmed = fromHeader.trim();
			if (isValid(trimmed)) {
				request.setAttribute(ATTRIBUTE, trimmed);
				return trimmed;
			}
		}
		String created = create();
		request.setAttribute(ATTRIBUTE, created);
		return created;
	}

	public static void bind(HttpServletRequest request, HttpServletResponse response) {
		String id = currentOrCreate(request);
		response.setHeader(HEADER, id);
	}

	public static void putMdc(String correlationId) {
		MDC.put(MDC_KEY, correlationId);
	}

	public static void clearMdc() {
		MDC.remove(MDC_KEY);
	}
}
