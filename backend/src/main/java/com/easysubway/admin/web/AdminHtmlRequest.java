package com.easysubway.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;

public final class AdminHtmlRequest {

	private AdminHtmlRequest() {
	}

	public static boolean matches(HttpServletRequest request) {
		String uri = request.getRequestURI();
		if (uri == null || !uri.startsWith("/admin/")) {
			return false;
		}
		String accept = request.getHeader("Accept");
		String contentType = request.getContentType();
		if (accept != null
			&& accept.contains(MediaType.APPLICATION_JSON_VALUE)
			&& !accept.contains(MediaType.TEXT_HTML_VALUE)) {
			return false;
		}
		return (accept != null && accept.contains(MediaType.TEXT_HTML_VALUE))
			|| uri.contains("/page")
			|| uri.startsWith("/admin/batches/")
			|| (contentType != null && contentType.contains(MediaType.APPLICATION_FORM_URLENCODED_VALUE));
	}
}
