package com.easysubway.route.application.port.out;

public class PlayIntegrityProviderUnavailableException extends RuntimeException {

	public PlayIntegrityProviderUnavailableException(Throwable cause) {
		super("Play Integrity provider is unavailable", cause);
	}
}
