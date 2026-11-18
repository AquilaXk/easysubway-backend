package com.easysubway.journey.activation;

import java.util.Objects;

public final class JourneyActivationException extends RuntimeException {

	public enum Kind {
		INVALID_REQUEST,
		CONFLICT,
		UNAVAILABLE
	}

	private final Kind kind;

	public JourneyActivationException(Kind kind) {
		super(Objects.requireNonNull(kind, "kind").name());
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}
}
