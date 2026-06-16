package com.easysubway.common.persistence;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProductionPersistenceReadinessConfiguration {

	private static final String MESSAGE = "운영 영속 저장소 구현이 필요합니다.";

	@Bean
	static BeanFactoryPostProcessor productionPersistenceReadinessGate() {
		// 운영에서는 사용자 데이터 유실 가능성이 있는 인메모리 저장소 fallback보다 명시적 실패를 우선한다.
		return beanFactory -> {
			throw new BeanCreationException(MESSAGE);
		};
	}
}
