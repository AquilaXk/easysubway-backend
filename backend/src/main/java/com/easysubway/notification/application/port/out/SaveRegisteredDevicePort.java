package com.easysubway.notification.application.port.out;

import com.easysubway.notification.domain.RegisteredDevice;

public interface SaveRegisteredDevicePort {

	RegisteredDevice saveRegisteredDevice(RegisteredDevice device);
}
