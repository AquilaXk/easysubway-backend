package com.easysubway.admin.authorization;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

public enum AdminRbacRole {
	ADMIN_VIEWER(AdminPermission.ADMIN_VIEW),
	REPORT_REVIEWER(AdminPermission.ADMIN_VIEW, AdminPermission.REPORT_REVIEW),
	MASTER_EDITOR(AdminPermission.ADMIN_VIEW, AdminPermission.MASTER_EDIT),
	FIELD_OPERATOR(AdminPermission.ADMIN_VIEW, AdminPermission.FIELD_OPERATE),
	DATA_OPERATOR(AdminPermission.ADMIN_VIEW, AdminPermission.DATA_OPERATE),
	SECURITY_ADMIN(AdminPermission.ADMIN_VIEW, AdminPermission.SECURITY_AUDIT, AdminPermission.SECURITY_ADMIN),
	SUPER_ADMIN(AdminPermission.values());

	private final Set<AdminPermission> permissions;

	AdminRbacRole(AdminPermission... permissions) {
		this.permissions = Set.copyOf(EnumSet.copyOf(Arrays.asList(permissions)));
	}

	public Set<AdminPermission> permissions() {
		return permissions;
	}
}
