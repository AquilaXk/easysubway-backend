package com.easysubway.journey.canary;

public final class JourneyCandidateCanaryException extends RuntimeException {

	private final Kind kind;

	public JourneyCandidateCanaryException(Kind kind) {
		super(kind.name());
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	public enum Kind {
		INVALID_REQUEST,
		CONFLICT,
		UNAVAILABLE
	}
}
