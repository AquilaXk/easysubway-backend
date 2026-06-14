package com.easysubway.favorite.application.port.out;

import java.util.List;

public interface LoadFavoriteFacilityAlertTargetPort {

	/**
	 * facilityId는 null이 아니어야 하며, 구현체는 null 입력을 즉시 거부한다.
	 */
	List<String> loadUserIdsByFavoriteFacilityId(String facilityId);
}
