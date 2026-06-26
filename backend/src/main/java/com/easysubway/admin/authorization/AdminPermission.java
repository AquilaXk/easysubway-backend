package com.easysubway.admin.authorization;

public enum AdminPermission {
	ADMIN_VIEW("admin.view"),
	REPORT_REVIEW("admin.report.review"),
	MASTER_EDIT("admin.master.edit"),
	FIELD_OPERATE("admin.field.operate"),
	DATA_OPERATE("admin.data.operate"),
	SECURITY_AUDIT("admin.security.audit"),
	SECURITY_ADMIN("admin.security.admin"),
	AUDIT_READ("admin.audit.read"),
	PRIVACY_LOG_READ("admin.privacy-log.read");

	private final String authority;

	AdminPermission(String authority) {
		this.authority = authority;
	}

	public String authority() {
		return authority;
	}
}
