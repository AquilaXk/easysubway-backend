package com.easysubway.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

public final class AdminHtmlRequest {

	private AdminHtmlRequest() {
	}

	public static boolean matches(HttpServletRequest request) {
		String uri = pathWithinApplication(request);
		if (uri == null || !uri.startsWith("/admin/")) {
			return false;
		}
		String accept = request.getHeader("Accept");
		if (accept != null
			&& accept.contains(MediaType.APPLICATION_JSON_VALUE)
			&& !accept.contains(MediaType.TEXT_HTML_VALUE)) {
			return false;
		}
		return (accept != null && accept.contains(MediaType.TEXT_HTML_VALUE))
			|| uri.contains("/page")
			|| uri.startsWith("/admin/batches/")
			|| isFormUrlEncoded(request);
	}

	public static String pathWithinApplication(HttpServletRequest request) {
		String uri = request.getRequestURI();
		if (uri == null) {
			return "";
		}
		String contextPath = request.getContextPath();
		if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
			return uri.substring(contextPath.length());
		}
		return uri;
	}

	public static boolean isFormUrlEncoded(HttpServletRequest request) {
		String contentType = request.getContentType();
		if (contentType == null) {
			return false;
		}
		try {
			return MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(MediaType.parseMediaType(contentType));
		} catch (InvalidMediaTypeException exception) {
			return false;
		}
	}
}
