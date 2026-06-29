package com.easysubway.admin.authorization;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

public enum AdminRbacRole {
	ADMIN_VIEWER(AdminPermission.ADMIN_VIEW, AdminPermission.DATAPACK_READ),
	REPORT_REVIEWER(AdminPermission.ADMIN_VIEW, AdminPermission.REPORT_REVIEW, AdminPermission.REPORT_PHOTO_READ),
	MASTER_EDITOR(
		AdminPermission.ADMIN_VIEW,
		AdminPermission.MASTER_EDIT,
		AdminPermission.DATAPACK_READ,
		AdminPermission.DATAPACK_ALIAS_REVIEW,
		AdminPermission.DATAPACK_QUARANTINE_REVIEW,
		AdminPermission.DATAPACK_EVIDENCE_REVIEW,
		AdminPermission.DATAPACK_OVERRIDE_REQUEST
	),
	FIELD_OPERATOR(
		AdminPermission.ADMIN_VIEW,
		AdminPermission.FIELD_OPERATE,
		AdminPermission.DATAPACK_READ,
		AdminPermission.DATAPACK_EVIDENCE_REVIEW,
		AdminPermission.DATAPACK_OVERRIDE_REQUEST
	),
	DATA_OPERATOR(
		AdminPermission.ADMIN_VIEW,
		AdminPermission.DATA_OPERATE,
		AdminPermission.BATCH_RETRY,
		AdminPermission.OPERATIONS_MANAGE,
		AdminPermission.DATAPACK_READ,
		AdminPermission.DATAPACK_SOURCE_RUN,
		AdminPermission.DATAPACK_CANDIDATE_BUILD,
		AdminPermission.DATAPACK_STAGING_PROMOTE
	),
	SECURITY_ADMIN(
		AdminPermission.ADMIN_VIEW,
		AdminPermission.SECURITY_AUDIT,
		AdminPermission.SECURITY_ADMIN,
		AdminPermission.AUDIT_READ,
		AdminPermission.PRIVACY_LOG_READ,
		AdminPermission.OPERATIONS_MANAGE,
		AdminPermission.DATAPACK_READ,
		AdminPermission.DATAPACK_AUDIT_READ
	),
	SUPER_ADMIN(AdminPermission.values());

	private final Set<AdminPermission> permissions;

	AdminRbacRole(AdminPermission... permissions) {
		this.permissions = Set.copyOf(EnumSet.copyOf(Arrays.asList(permissions)));
	}

	public Set<AdminPermission> permissions() {
		return permissions;
	}
}
