package com.easysubway.journey.bundle;

import java.time.Clock;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("(prod | staging | release | prod-like) & !capacity-evidence")
@EnableConfigurationProperties(RouteBundleStartupProperties.class)
class RouteBundleStartupConfiguration {

	@Bean
	RouteBundlePublicationObjectFetcher routeBundlePublicationObjectFetcher(
		RouteBundleStartupProperties properties) {
		return new RouteBundlePublicationObjectFetcher(properties.trustedRawDescriptorBaseUrl());
	}

	@Bean
	RouteBundleCandidateAssembler routeBundleCandidateAssembler() {
		return new RouteBundleCandidateAssembler();
	}

	@Bean
	RouteBundleStartupCandidateLoader routeBundleStartupCandidateLoader(
		RouteBundleStartupProperties properties,
		RouteBundlePublicationObjectFetcher fetcher,
		RouteBundleCandidateAssembler assembler,
		RouteBundleActivationRegistry registry) {
		return new RouteBundleStartupCandidateLoader(
			properties,
			fetcher,
			RouteBundleObjectAdmission::admitPublicationDescriptor,
			assembler,
			registry,
			Clock.systemUTC());
	}

	@Bean
	SmartInitializingSingleton routeBundleStartupCandidateStager(
		RouteBundleStartupCandidateLoader loader) {
		return loader::loadAndStage;
	}
}
