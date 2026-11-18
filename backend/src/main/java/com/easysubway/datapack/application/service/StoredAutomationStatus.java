package com.easysubway.datapack.application.service;

import com.easysubway.datapack.domain.AutomationStatusSnapshot;
import java.time.Instant;

/** 저장소에서 읽어 검증을 다시 통과한 snapshot과 수신 시각. */
public record StoredAutomationStatus(AutomationStatusSnapshot snapshot, Instant receivedAt) {
}
