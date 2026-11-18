package com.easysubway.journey.application;

/** Immutable, non-wire handle to one realtime overlay bound to a compiled RAPTOR generation. */
public interface JourneyRaptorRealtimeView {

	String identity();

	String routeBundleSha256();

	long generation();
}
