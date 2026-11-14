package com.easysubway.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AdminHtmlRequest {

	private static final Set<String> STATE_CHANGING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

	private AdminHtmlRequest() {
	}

	public static boolean isStateChangingMethod(HttpServletRequest request) {
		if (request == null || request.getMethod() == null) {
			return false;
		}
		return STATE_CHANGING_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT));
	}

	public static boolean isSessionAuthenticated(HttpServletRequest request) {
		if (request == null) {
			return false;
		}
		String authorization = request.getHeader("Authorization");
		if (authorization != null) {
			String normalized = authorization.trim().toLowerCase(Locale.ROOT);
			if (normalized.startsWith("basic ") || normalized.startsWith("bearer ")) {
				return false;
			}
		}
		if (request.getSession(false) != null) {
			return true;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null
			&& authentication.isAuthenticated()
			&& !(authentication instanceof AnonymousAuthenticationToken);
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
			|| isBrowserFormContentType(request);
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

	public static boolean isBrowserFormContentType(HttpServletRequest request) {
		String contentType = request.getContentType();
		if (contentType == null) {
			return false;
		}
		try {
			MediaType mediaType = MediaType.parseMediaType(contentType);
			return MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(mediaType)
				|| MediaType.MULTIPART_FORM_DATA.isCompatibleWith(mediaType)
				|| MediaType.TEXT_PLAIN.isCompatibleWith(mediaType);
		} catch (InvalidMediaTypeException exception) {
			return false;
		}
	}
}
