package com.easysubway.admin.transition;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminPlatformTransitionProperties.class)
class AdminPlatformTransitionConfiguration {
}
