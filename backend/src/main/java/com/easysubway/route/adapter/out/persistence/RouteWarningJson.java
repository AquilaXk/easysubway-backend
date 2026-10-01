package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

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

	public static List<RouteWarningJson> fromAll(List<RouteWarning> warnings) {
		return warnings.stream().map(RouteWarningJson::from).toList();
	}

	public static List<RouteWarning> toDomainAll(List<RouteWarningJson> dtoList) {
		return dtoList.stream().map(RouteWarningJson::toDomain).toList();
	}
}
