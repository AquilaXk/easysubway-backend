package com.easysubway.route.domain.fixture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;

@Component
@JsonIgnoreProperties(ignoreUnknown = true)
final class DomainDependsOnFrameworkFixture {
}
