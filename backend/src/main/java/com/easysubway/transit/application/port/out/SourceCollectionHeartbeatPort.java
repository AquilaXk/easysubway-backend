package com.easysubway.transit.application.port.out;

import java.time.Instant;

/**
 * Port providing the heartbeat (last successful collection timestamp) of an external
 * official transit facility status data source.
 */
@FunctionalInterface
public interface SourceCollectionHeartbeatPort {

	/**
	 * Returns the timestamp of the last successful collection from the official source,
	 * or {@code null} if no collection has succeeded yet.
	 */
	Instant lastSuccessfulSourceCollectionAt();
}
