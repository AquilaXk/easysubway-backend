package com.easysubway.favorite.adapter.out.persistence;

import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record RouteWarningJson(
	RouteWarningCode code
) {
	static RouteWarningJson from(RouteWarning warning) {
		return new RouteWarningJson(warning.code());
	}

	RouteWarning toDomain() {
		return new RouteWarning(code);
	}
}
