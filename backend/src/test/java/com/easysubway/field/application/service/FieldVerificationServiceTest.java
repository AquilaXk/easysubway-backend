package com.easysubway.field.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.field.domain.FieldVerificationItemType;
import com.easysubway.field.domain.FieldVerificationStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("현장 검증 기준선")
class FieldVerificationServiceTest {

	private final FieldVerificationService service = new FieldVerificationService();

	@Test
	@DisplayName("상록수역 필수 현장 검증 항목을 조회한다")
	void getsSangnoksuFieldVerificationBaseline() {
		var session = service.getStationVerification("station-sangnoksu");

		assertThat(session.id()).isEqualTo("field-verification-sangnoksu-2026-06");
		assertThat(session.stationId()).isEqualTo("station-sangnoksu");
		assertThat(session.stationName()).isEqualTo("상록수역");
		assertThat(session.verifiedAt()).isEqualTo(LocalDate.of(2026, 6, 19));
		assertThat(session.verifiedBy()).isEqualTo("field-team");
		assertThat(session.status()).isEqualTo(FieldVerificationStatus.IN_PROGRESS);
		assertThat(session.items())
			.extracting(item -> item.type())
			.containsExactly(
				FieldVerificationItemType.EXIT,
				FieldVerificationItemType.ELEVATOR,
				FieldVerificationItemType.ESCALATOR,
				FieldVerificationItemType.RESTROOM,
				FieldVerificationItemType.PLATFORM_TRANSFER
			);
		assertThat(session.items())
			.extracting(item -> item.status())
			.containsOnly(FieldVerificationStatus.PLANNED, FieldVerificationStatus.VERIFIED);
	}
}
