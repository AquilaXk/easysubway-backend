package com.easysubway.route.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteWarning(
	RouteWarningCode code
) {
}
