package com.easysubway.report.adapter.out.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class ObjectStorageUrlEncoding {

	private ObjectStorageUrlEncoding() {
	}

	static String encodePath(String value) {
		return java.util.Arrays.stream(value.split("/", -1))
			.map(ObjectStorageUrlEncoding::encodePathSegment)
			.reduce((left, right) -> left + "/" + right)
			.orElse("");
	}

	static String encodePathSegment(String value) {
		return urlEncode(value).replace("%2F", "/");
	}

	private static String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8)
			.replace("+", "%20")
			.replace("*", "%2A")
			.replace("%7E", "~");
	}
}
