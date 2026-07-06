package com.easysubway.collection.application.port.out;

/**
 * 수집 실행(run) 식별자 발번 포트.
 *
 * <p>application 계층은 이 포트만 알고, 발번 구현(eGovFrame fdl-idgnr Table 전략 + fdl-property
 * 접두어)은 adapter가 담당한다(헥사고날 경계 유지). 발번 대상은 운영성 수집 run ID뿐이며,
 * 보안 식별자(UUID·DB PK·receipt token 등)는 이 포트를 통하지 않는다.
 */
public interface GenerateCollectionRunIdPort {

	/**
	 * 다음 수집 run ID를 발번한다. 포맷은 {@code <prefix><id>}이며 접두어는 운영성 property로 외부화한다.
	 *
	 * @return 수집 run ID (예: {@code collection-1})
	 */
	String nextCollectionRunId();
}
