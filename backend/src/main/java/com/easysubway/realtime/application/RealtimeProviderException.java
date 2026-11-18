package com.easysubway.realtime.application;

public class RealtimeProviderException extends RuntimeException {

	private final String providerCause;

	public RealtimeProviderException(String providerCause) {
		super(providerCause);
		this.providerCause = providerCause;
	}

	public String providerCause() {
		return providerCause;
	}
}
