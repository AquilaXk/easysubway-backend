package com.easysubway.journey.config;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationService;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneySessionIntegrityPort;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.JourneySessionStore;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleActiveJourneySnapshotAdapter;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.easysubway.route.application.service.JourneyRealtimeAdapter;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@Profile("(prod | staging | release | prod-like) & !capacity-evidence")
@EnableConfigurationProperties(JourneySearchPolicyProperties.class)
public class JourneyProductionConfiguration {

	private static final String SESSION_PATH = "/api/v3/journeys/session";
	private static final String SEARCH_PATH = "/api/v3/journeys/search";
	private static final Clock CLOCK = Clock.systemUTC();
	private static final Duration REALTIME_FRESHNESS_TTL = Duration.ofSeconds(90);

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
			CLOCK,
			new SecureRandom(),
			certificateSha256,
			searchPolicy.maxSearchesPerSession()
		);
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	ActiveJourneySnapshotPort activeJourneySnapshotPort(RouteBundleActivationRegistry registry) {
		return new RouteBundleActiveJourneySnapshotAdapter(registry);
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyRaptorPort journeyRaptorPort() {
		return new JourneyRaptorAdapter();
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyRealtimePort journeyRealtimePort(JourneyTimetableRealtimeResolver resolver) {
		return new JourneyRealtimeAdapter(resolver, CLOCK, REALTIME_FRESHNESS_TTL);
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyApplicationService journeyApplicationService(
		ActiveJourneySnapshotPort activeSnapshotPort,
		JourneyRealtimePort realtimePort,
		JourneyRaptorPort raptorPort
	) {
		return new JourneyApplicationService(activeSnapshotPort, realtimePort, raptorPort, CLOCK);
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	ExecutorService journeyApplicationExecutor() {
		return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("journey-search-", 0).factory());
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyApplicationDeadlineExecutor journeyApplicationDeadlineExecutor(
		JourneyApplicationService service,
		@Qualifier("journeyApplicationExecutor") ExecutorService executor,
		JourneySearchPolicyProperties searchPolicy
	) {
		return new JourneyApplicationDeadlineExecutor(service, executor, searchPolicy.timeout());
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
