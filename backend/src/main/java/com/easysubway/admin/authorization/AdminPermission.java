package com.easysubway.admin.authorization;

public enum AdminPermission {
	ADMIN_VIEW("admin.view"),
	REPORT_REVIEW("admin.report.review"),
	REPORT_PHOTO_READ("admin.report.photo.read"),
	MASTER_EDIT("admin.master.edit"),
	FIELD_OPERATE("admin.field.operate"),
	DATA_OPERATE("admin.data.operate"),
	SECURITY_AUDIT("admin.security.audit"),
	SECURITY_ADMIN("admin.security.admin"),
	AUDIT_READ("admin.audit.read"),
	PRIVACY_LOG_READ("admin.privacy-log.read"),
	BATCH_RETRY("admin.batch.retry"),
	OPERATIONS_MANAGE("admin.operations.manage"),
	DATAPACK_READ("admin.datapack.read"),
	DATAPACK_SOURCE_RUN("admin.datapack.source.run"),
	DATAPACK_ALIAS_REVIEW("admin.datapack.alias.review"),
	DATAPACK_QUARANTINE_REVIEW("admin.datapack.quarantine.review"),
	DATAPACK_EVIDENCE_REVIEW("admin.datapack.evidence.review"),
	DATAPACK_OVERRIDE_REQUEST("admin.datapack.override.request"),
	DATAPACK_OVERRIDE_APPROVE("admin.datapack.override.approve"),
	DATAPACK_CANDIDATE_BUILD("admin.datapack.candidate.build"),
	DATAPACK_STAGING_PROMOTE("admin.datapack.staging.promote"),
	DATAPACK_PRODUCTION_APPROVE("admin.datapack.production.approve"),
	DATAPACK_ROLLBACK("admin.datapack.rollback"),
	DATAPACK_AUDIT_READ("admin.datapack.audit.read");

	private final String authority;

	AdminPermission(String authority) {
		this.authority = authority;
	}

	public String authority() {
		return authority;
	}
}
