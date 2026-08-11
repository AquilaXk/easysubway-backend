package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

@DisplayName("운영 계열 Flyway baseline 정책")
class ProductionFlywayBaselinePolicyTest {

	private static final List<String> RELEASE_PROFILES = List.of("prod", "staging", "release", "prod-like");

	@Test
	@DisplayName("모든 운영 계열 프로필은 자동 baseline을 명시적으로 비활성화한다")
	void releaseProfilesDisableAutomaticBaseline() throws IOException {
		PropertySource<?> common = load("application.yml");
		PropertySource<?> production = load("application-prod.yml");

		assertThat(production.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(false);
		assertThat(RELEASE_PROFILES).allSatisfy(profile -> {
			if (profile.equals("prod")) {
				return;
			}
			assertThat(common.getProperty("spring.profiles.group." + profile)).isEqualTo("prod");
		});
	}

	private PropertySource<?> load(String resource) throws IOException {
		var sources = new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
		assertThat(sources).hasSize(1);
		return sources.getFirst();
	}
}
