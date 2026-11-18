package com.easysubway.datapack.domain;

import java.time.LocalDateTime;

public final class DatapackFreshness {

	private DatapackFreshness() {
	}

	public static boolean isStale(LocalDateTime evaluationAt, LocalDateTime expiresAt) {
		return !evaluationAt.isBefore(expiresAt);
	}
}
