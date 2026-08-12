package com.easysubway.journey.config;

import com.easysubway.journey.application.JourneySessionIntegrityPort;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.JourneySessionStore;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@Profile("prod | staging | release | prod-like")
@EnableConfigurationProperties(JourneySearchPolicyProperties.class)
public class JourneyProductionConfiguration {

	private static final String SESSION_PATH = "/api/v3/journeys/session";
	private static final String SEARCH_PATH = "/api/v3/journeys/search";

	@Bean
	JourneySessionService journeySessionService(
		JourneySessionIntegrityPort integrityPort,
		JourneySessionStore sessionStore,
		JourneySearchPolicyProperties searchPolicy,
		@Value("${easysubway.journey.session.certificate-sha256}") String certificateSha256
	) {
		return new JourneySessionService(
			integrityPort,
			sessionStore,
			Clock.systemUTC(),
			new SecureRandom(),
			certificateSha256,
			searchPolicy.maxSearchesPerSession()
		);
	}

	@Bean
	@Order(4)
	SecurityFilterChain journeyV3IngressSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
			.securityMatcher(SESSION_PATH, SEARCH_PATH)
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(HttpMethod.POST, SESSION_PATH, SEARCH_PATH).permitAll()
				.anyRequest().denyAll()
			)
			.build();
	}
}
