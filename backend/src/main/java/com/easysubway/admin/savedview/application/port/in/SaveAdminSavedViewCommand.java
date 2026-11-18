package com.easysubway.admin.savedview.application.port.in;

public record SaveAdminSavedViewCommand(
	String adminLoginId,
	String programId,
	String name,
	String queryParams,
	boolean makeDefault
) {
}
