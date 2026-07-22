package com.easysubway.route.adapter.in.web;

import com.easysubway.common.error.CorrelationId;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

final class RouteV2ErrorWriter {

	private RouteV2ErrorWriter() {
	}

	static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
		String correlationId = response.getHeader(CorrelationId.HEADER);
		if (correlationId == null || correlationId.isBlank()) {
			correlationId = CorrelationId.create();
			response.setHeader(CorrelationId.HEADER, correlationId);
		}
		response.setStatus(status);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
		response.getWriter().write(
			"{\"success\":false,\"code\":\"" + code
				+ "\",\"message\":\"" + message
				+ "\",\"correlationId\":\"" + correlationId + "\"}"
		);
	}
}
