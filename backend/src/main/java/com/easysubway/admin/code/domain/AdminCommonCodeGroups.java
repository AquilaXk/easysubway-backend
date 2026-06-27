package com.easysubway.admin.code.domain;

import java.util.Set;

public final class AdminCommonCodeGroups {

	public static final String REPORT_REJECTION_REASON = "REPORT_REJECTION_REASON";
	public static final String FACILITY_STATUS_REASON = "FACILITY_STATUS_REASON";
	public static final String BATCH_FAILURE_CATEGORY = "BATCH_FAILURE_CATEGORY";
	public static final String INCIDENT_SEVERITY = "INCIDENT_SEVERITY";
	public static final String INCIDENT_STATUS = "INCIDENT_STATUS";
	public static final String INCIDENT_SOURCE = "INCIDENT_SOURCE";

	private static final Set<String> REQUIRED_INCIDENT_CODES = Set.of(
		INCIDENT_SEVERITY + ":MAJOR",
		INCIDENT_SEVERITY + ":MINOR",
		INCIDENT_STATUS + ":OPEN",
		INCIDENT_STATUS + ":RESOLVED",
		INCIDENT_SOURCE + ":HEALTH"
	);

	private AdminCommonCodeGroups() {
	}

	public static boolean isRequiredIncidentCode(String groupCode, String code) {
		return REQUIRED_INCIDENT_CODES.contains(groupCode + ":" + code);
	}
}
