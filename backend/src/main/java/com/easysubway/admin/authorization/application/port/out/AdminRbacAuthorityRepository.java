package com.easysubway.admin.authorization.application.port.out;

import java.util.Set;

public interface AdminRbacAuthorityRepository {

	Set<String> findPermissionAuthorities(String loginId);
}
