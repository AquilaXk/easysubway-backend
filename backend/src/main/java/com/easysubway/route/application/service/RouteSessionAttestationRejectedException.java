package com.easysubway.route.application.service;

public class RouteSessionAttestationRejectedException extends RuntimeException {

	public RouteSessionAttestationRejectedException() {
		super("Route V2 session attestation rejected");
	}

	public RouteSessionAttestationRejectedException(Throwable cause) {
		super("Route V2 session attestation rejected", cause);
	}
}
