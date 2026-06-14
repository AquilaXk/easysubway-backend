package com.easysubway.auth.adapter.in.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "easysubway.auth.client-ip")
class AnonymousAuthClientIpProperties {

	private List<String> trustedProxies = new ArrayList<>();

	public List<String> getTrustedProxies() {
		return trustedProxies;
	}

	public void setTrustedProxies(List<String> trustedProxies) {
		this.trustedProxies = trustedProxies == null ? new ArrayList<>() : trustedProxies;
	}
}
