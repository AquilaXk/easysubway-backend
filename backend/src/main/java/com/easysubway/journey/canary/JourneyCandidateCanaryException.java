package com.easysubway.journey.canary;

public final class JourneyCandidateCanaryException extends RuntimeException {

	private final Kind kind;
	private final FailureReason failureReason;
	private final String probeId;

	public JourneyCandidateCanaryException(Kind kind) {
		this(kind, null, null);
	}

	public JourneyCandidateCanaryException(Kind kind, FailureReason failureReason, String probeId) {
		super(kind.name());
		this.kind = kind;
		this.failureReason = failureReason;
		this.probeId = probeId;
	}

	public Kind kind() {
		return kind;
	}

	/** {@code UNAVAILABLE}의 세부 사유. 구분되지 않는 실패(CONFLICT 등)에서는 null이다. */
	public FailureReason failureReason() {
		return failureReason;
	}

	/** 실패한 probe의 requestId. command를 해석하기 전의 실패에서는 null이다. */
	public String probeId() {
		return probeId;
	}

	public enum Kind {
		INVALID_REQUEST,
		CONFLICT,
		UNAVAILABLE
	}

	public enum FailureReason {
		SNAPSHOT_ERROR,
		WINDOW_MISMATCH,
		PLAN_ERROR,
		NO_CANDIDATES
	}
}
