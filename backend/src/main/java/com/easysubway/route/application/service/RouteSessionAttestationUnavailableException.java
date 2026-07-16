package com.easysubway.route.application.service;

public class RouteSessionAttestationUnavailableException extends RuntimeException {

	public RouteSessionAttestationUnavailableException(Throwable cause) {
		super("Route V2 session attestation is unavailable", cause);
	}
}
