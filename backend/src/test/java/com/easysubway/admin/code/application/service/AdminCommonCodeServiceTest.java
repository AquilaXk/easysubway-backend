package com.easysubway.admin.code.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.admin.code.adapter.out.persistence.InMemoryAdminCommonCodeRepository;
import com.easysubway.admin.code.application.service.AdminCommonCodeService.SaveAdminCommonCodeCommand;
import com.easysubway.admin.code.domain.AdminCommonCode;
import com.easysubway.admin.code.domain.AdminCommonCodeGroups;
import com.easysubway.common.error.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 공통코드 서비스")
class AdminCommonCodeServiceTest {

	private final AdminCommonCodeService service = new AdminCommonCodeService(new InMemoryAdminCommonCodeRepository());

	@Test
	@DisplayName("공통코드는 group별로 저장하고 disabled code는 신규 선택 목록에서 제외한다")
	void saveAndDisableCommonCode() {
		AdminCommonCode saved = service.saveCode(new SaveAdminCommonCodeCommand(
			AdminCommonCodeGroups.REPORT_REJECTION_REASON,
			"OUT_OF_SCOPE",
			"처리 범위 아님",
			"앱 처리 범위 밖의 제보",
			30,
			true
		));

		assertThat(saved.enabled()).isTrue();
		assertThat(service.enabledCodes(AdminCommonCodeGroups.REPORT_REJECTION_REASON))
			.extracting(AdminCommonCode::code)
			.contains("OUT_OF_SCOPE");

		service.disableCode(AdminCommonCodeGroups.REPORT_REJECTION_REASON, "OUT_OF_SCOPE");

		assertThat(service.enabledCodes(AdminCommonCodeGroups.REPORT_REJECTION_REASON))
			.extracting(AdminCommonCode::code)
			.doesNotContain("OUT_OF_SCOPE");
		assertThat(service.listCodes(AdminCommonCodeGroups.REPORT_REJECTION_REASON, true))
			.extracting(AdminCommonCode::code)
			.contains("OUT_OF_SCOPE");
	}

	@Test
	@DisplayName("공통코드 식별자는 path-safe 문자만 허용한다")
	void commonCodeIdentifierMustBePathSafe() {
		assertThatThrownBy(() -> service.saveCode(new SaveAdminCommonCodeCommand(
			AdminCommonCodeGroups.REPORT_REJECTION_REASON,
			"BAD/CODE",
			"잘못된 코드",
			"disable URL path segment로 표현할 수 없는 코드",
			40,
			true
		)))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("공통코드 code는 영문 대문자, 숫자, 밑줄만 1~64자까지 허용됩니다.");
	}

	@Test
	@DisplayName("incident lifecycle 필수 코드는 비활성화할 수 없다")
	void requiredIncidentCodesCannotBeDisabled() {
		assertThatThrownBy(() -> service.disableCode(AdminCommonCodeGroups.INCIDENT_STATUS, "OPEN"))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("필수 incident 공통코드는 비활성화할 수 없습니다.");

		assertThat(service.enabledCodes(AdminCommonCodeGroups.INCIDENT_STATUS))
			.extracting(AdminCommonCode::code)
			.contains("OPEN");
	}

	@Test
	@DisplayName("incident lifecycle 필수 코드는 저장 경로에서도 enabled 상태를 유지한다")
	void requiredIncidentCodesRemainEnabledOnSave() {
		AdminCommonCode saved = service.saveCode(new SaveAdminCommonCodeCommand(
			AdminCommonCodeGroups.INCIDENT_STATUS,
			"OPEN",
			"Open",
			"처리 전",
			10,
			false
		));

		assertThat(saved.enabled()).isTrue();
		assertThat(service.enabledCodes(AdminCommonCodeGroups.INCIDENT_STATUS))
			.extracting(AdminCommonCode::code)
			.contains("OPEN");
	}

	@Test
	@DisplayName("incident lifecycle 필수 코드는 공백 포함 저장 경로에서도 enabled 상태를 유지한다")
	void requiredIncidentCodesRemainEnabledOnTrimmedSave() {
		AdminCommonCode saved = service.saveCode(new SaveAdminCommonCodeCommand(
			" " + AdminCommonCodeGroups.INCIDENT_STATUS + " ",
			" OPEN ",
			"Open",
			"처리 전",
			10,
			false
		));

		assertThat(saved.groupCode()).isEqualTo(AdminCommonCodeGroups.INCIDENT_STATUS);
		assertThat(saved.code()).isEqualTo("OPEN");
		assertThat(saved.enabled()).isTrue();
		assertThat(service.enabledCodes(AdminCommonCodeGroups.INCIDENT_STATUS))
			.extracting(AdminCommonCode::code)
			.contains("OPEN");
	}
}
