package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteWarningJson(
	RouteWarningCode code
) {
	public static RouteWarningJson from(RouteWarning warning) {
		return new RouteWarningJson(warning.code());
	}

	public RouteWarning toDomain() {
		return new RouteWarning(code);
	}
}
