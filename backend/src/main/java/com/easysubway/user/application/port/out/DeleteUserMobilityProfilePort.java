package com.easysubway.user.application.port.out;

public interface DeleteUserMobilityProfilePort {

	/**
	 * @return 삭제 대상 이동 프로필이 존재해 제거됐으면 true, 없었으면 false
	 */
	boolean deleteMobilityProfile(String userId);
}
