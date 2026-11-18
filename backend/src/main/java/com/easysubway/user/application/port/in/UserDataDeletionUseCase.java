package com.easysubway.user.application.port.in;

import com.easysubway.user.domain.UserDataDeletionResult;

public interface UserDataDeletionUseCase {

	/**
	 * 인증 사용자에게 연결된 데이터를 삭제하고 운영 보존이 필요한 기록은 익명화한다.
	 *
	 * @param userId 삭제 대상 사용자 식별자. null이나 공백은 허용하지 않는다.
	 * @return 저장소별 삭제/익명화 처리 건수
	 */
	UserDataDeletionResult deleteUserData(String userId);
}
