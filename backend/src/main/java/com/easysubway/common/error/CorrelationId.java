package com.easysubway.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

public final class CorrelationId {

	public static final String HEADER = "X-Correlation-Id";
	public static final String ATTRIBUTE = CorrelationId.class.getName();

	private CorrelationId() {
	}

	public static String create() {
		return UUID.randomUUID().toString();
	}

	public static String currentOrCreate(HttpServletRequest request) {
		Object existing = request.getAttribute(ATTRIBUTE);
		if (existing instanceof String value && !value.isBlank()) {
			return value;
		}
		String created = create();
		request.setAttribute(ATTRIBUTE, created);
		return created;
	}

	public static void bind(HttpServletRequest request, HttpServletResponse response) {
		String id = currentOrCreate(request);
		response.setHeader(HEADER, id);
	}
}
