package com.easysubway.admin.authorization.application.port.out;

import com.easysubway.admin.authorization.AdminRbacRole;
import java.util.Set;

public interface AdminRbacAuthorityRepository {

	Set<String> findPermissionAuthorities(String loginId);

	/**
	 * env로 지정된 관리자 계정이 부팅 시점에 지정 RBAC role을 반드시 보유하도록 보증한다.
	 * 이미 동일한 role 할당이 있으면 아무 것도 하지 않으며(멱등), 로그인 ID는 저장소에
	 * 하드코딩하지 않고 호출 시점에 전달받은 값만 사용한다. bootstrap이 만든 행에는 부여
	 * 출처(provenance)를 남겨 회수 대상과 수동 부여 행을 구분한다. 기존 행이 있으면(수동
	 * 부여 포함) 덮지 않으므로 운영자가 직접 부여한 role은 seed가 침범하지 않는다.
	 */
	void seedRole(String loginId, AdminRbacRole role);

	/**
	 * bootstrap seed가 만든 role 할당 중 현재 active bootstrap 로그인 ID 목록에 없는 행만
	 * 회수한다. {@link #seedRole}의 대칭 회수 경로로, env에서 계정이 제거되면 그 계정의
	 * bootstrap-seeded role을 정리한다. 수동 부여 행(bootstrap provenance가 아닌 행)은
	 * 절대 삭제하지 않는다. 목록이 비어 있으면 모든 bootstrap-seeded 행을 회수한다.
	 */
	void revokeStaleBootstrapRoles(Set<String> activeBootstrapLoginIds);
}
