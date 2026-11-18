package com.easysubway.transit.application.port.out;

import java.time.Instant;

public record MasterDataCapability(
	MasterDataCapabilityStatus status,
	boolean readable,
	boolean writable,
	String artifactVersion,
	String sha256,
	Instant loadedAt
) {
}
