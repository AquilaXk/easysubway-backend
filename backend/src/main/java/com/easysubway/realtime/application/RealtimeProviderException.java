package com.easysubway.realtime.application;

public class RealtimeProviderException extends RuntimeException {

	private final String fallbackCode;

	public RealtimeProviderException(String fallbackCode) {
		super(fallbackCode);
		this.fallbackCode = fallbackCode;
	}

	public String fallbackCode() {
		return fallbackCode;
	}
}
