package com.easysubway.train.application;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

public interface TrainSearchCache {

	Optional<CachedCatalog> freshCatalog(String kind, Instant now);

	void replaceCatalog(List<CachedCatalog> catalogs);

	Optional<CachedLeg> freshLeg(String key, Instant now);

	boolean tryAcquireLease(String key, String owner, Instant now, Duration ttl);

	void releaseLease(String key, String owner);

	boolean storeLegAndRelease(String key, String owner, CachedLeg leg);

	boolean tryAcquireProviderCall(String providerId, ZoneId providerZone, int minuteLimit, int dayLimit);

	int purgeExpiredBefore(Instant cutoff);

	record CachedCatalog(
		String kind,
		String payloadJson,
		String payloadSha256,
		Instant observedAt,
		Instant expiresAt
	) {}

	record CachedLeg(
		String key,
		String normalizedQueryJson,
		String payloadJson,
		String payloadSha256,
		Instant observedAt,
		Instant expiresAt
	) {}
}
