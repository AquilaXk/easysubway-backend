package com.easysubway.notification.application.port.in;

public interface FacilityStatusAlertUseCase {

	void alertFacilityStatusChanged(FacilityStatusChangedAlertCommand command);
}
