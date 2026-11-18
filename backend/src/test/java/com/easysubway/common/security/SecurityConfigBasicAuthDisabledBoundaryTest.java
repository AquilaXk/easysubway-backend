package com.easysubway.common.security;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.TestPropertySource;

/**
 * 운영 프로필처럼 Basic auth를 끈 상태의 인증 진입점 경계(#526). Basic을 켜면 Basic의 401 진입점이
 * 비HTML 요청을 먼저 받아 `/admin/**`·`/operator/api/**` 진입점 matcher가 가려지므로, matcher 자체의
 * 경계는 Basic을 끈 컨텍스트에서만 드러난다.
 */
@TestPropertySource(properties = "easysubway.admin.basic-auth.enabled=false")
@DisplayName("보안 설정 경로 경계(Basic auth 비활성)")
class SecurityConfigBasicAuthDisabledBoundaryTest extends SecurityPathBoundarySupport {

	/** 익명 요청의 진입점 결과는 Basic 설정과 무관하게 Basic 활성 컨텍스트 표와 같아야 한다. */
	static Stream<Arguments> entryPointCases() {
		Stream<Arguments> anonymousRows = Stream.concat(
				SecurityConfigPathBoundaryTest.adminChainBoundaryCases(),
				SecurityConfigPathBoundaryTest.operatorChainCases()
			)
			.map(arguments -> (Case) arguments.get()[0])
			.filter(testCase -> testCase.caller() == ANONYMOUS)
			.map(Arguments::of);
		Stream<Arguments> basicCredentialRows = Stream.of(
			// Basic이 꺼져 있으면 Authorization 헤더는 무시되어 자격 증명을 보내도 익명과 같다.
			Case.of(SUPER_ADMIN, "GET", "/admin/dashboard/page", 401),
			Case.of(SUPER_ADMIN, "GET", "/admin/dashboard/page", 302).accept("text/html").redirectsTo("/admin/login"),
			Case.of(OPERATOR, "GET", "/operator/accessibility-report/page", 302).redirectsTo("/operator/login"),
			Case.of(OPERATOR, "GET", "/operator/api/reports", 401)
		).map(Arguments::of);
		return Stream.concat(anonymousRows, basicCredentialRows);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("entryPointCases")
	@DisplayName("Basic auth를 꺼도 인증 진입점은 경로 변형에 같은 결과를 낸다")
	void entryPointBoundaryWithoutBasicAuth(Case testCase) throws Exception {
		assertOutcome(testCase);
	}
}
