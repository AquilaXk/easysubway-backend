package com.easysubway.journey.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

@DisplayName("Journey 운영 graceful shutdown 설정")
class JourneyGracefulShutdownConfigurationTest {

	@Test
	@DisplayName("운영 프로필은 graceful shutdown과 30초 phase 상한을 고정한다")
	void productionProfilePinsGracefulShutdownAndBound() throws IOException {
		PropertySource<?> production = new YamlPropertySourceLoader()
			.load(
				"application-prod.yml",
				new FileSystemResource(Path.of("src/main/resources/application-prod.yml"))
			)
			.getFirst();

		assertThat(production.getProperty("server.shutdown")).isEqualTo("graceful");
		assertThat(production.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
			.isEqualTo("30s");
	}
}
