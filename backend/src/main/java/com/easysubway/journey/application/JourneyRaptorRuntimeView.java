package com.easysubway.journey.application;

/** Immutable, non-wire handle to one compiled Journey RAPTOR generation. */
public interface JourneyRaptorRuntimeView {

	String routeBundleSha256();

	long generation();
}
