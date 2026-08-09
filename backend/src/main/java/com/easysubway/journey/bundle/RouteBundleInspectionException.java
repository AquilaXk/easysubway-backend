package com.easysubway.journey.bundle;

final class RouteBundleInspectionException extends RuntimeException {
	private final Reason reason;

	RouteBundleInspectionException(Reason reason) {
		super(reason.name());
		this.reason = reason;
	}

	Reason reason() {
		return reason;
	}

	enum Reason {
		MANIFEST_UTF8_OR_JSON_INVALID,
		MANIFEST_DUPLICATE_FIELD,
		MANIFEST_SCHEMA_INVALID,
		PAYLOAD_PATH_SET_MISMATCH,
		TOPOLOGY_DIGEST_MISMATCH,
		TIMETABLE_DIGEST_MISMATCH,
		ACCESSIBILITY_DIGEST_MISMATCH,
		FARE_DIGEST_MISMATCH,
		PAYLOAD_INVENTORY_DIGEST_MISMATCH
	}
}
