package com.easysubway.journey.bundle;

/** Structural inspection result; this is deliberately not an admission candidate. */
record RouteBundlePayloadInspection(RouteBundleIdentity identity, String manifestSha256, String payloadSha256) {
}
