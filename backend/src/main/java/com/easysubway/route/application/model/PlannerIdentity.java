package com.easysubway.route.application.model;

public record PlannerIdentity(
	String timetableSnapshotSha256,
	String canonicalPackSha256,
	String canonicalPackSqliteSha256,
	String canonicalStationVersion,
	String canonicalStationSetSha256,
	String sourceLineageSha256,
	String evidenceHash
) {
}
