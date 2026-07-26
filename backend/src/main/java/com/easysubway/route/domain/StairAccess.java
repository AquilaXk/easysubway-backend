package com.easysubway.route.domain;

import java.util.List;

/**
 * 계단 접근성 판정의 단일 원천(#2590).
 *
 * <p>{@link RouteStep#stairAccessState()}는 판정 결과가 아니라 원자료다. 기본 파생에
 * {@code STEP_FREE} 분기가 없어 계단 신호가 하나도 없는 승차 구간까지 {@code UNKNOWN}으로
 * 적힌다. 그 값을 표시 계층이 다시 판정하면 "확인해 봤더니 미상"과 "애초에 계단 개념이
 * 없음"이 한 토큰으로 뭉개진다. 판정은 여기서만 하고 결과를 응답에 실어 보낸다.
 *
 * <p><b>leg 판정과 경로 판정은 같은 값이 아니며, 일치를 계약으로 두지 않는다.</b> leg 판정
 * ({@link #ofStep(RouteStep)})은 그 구간 자신의 계단 사실만 담는다. 경로 판정
 * ({@link #ofItineraryDisplay(RouteSearchResult)})은 거기에 어느 구간에도 매달 수 없는 경로
 * 단위 신호를 겹치므로 leg를 접은 값보다 더 신중할 수 있고, 그 차이가 의도다. 경로 단위
 * 신호를 leg마다 복제하면 그 신호와 무관하게 검증된 구간까지 미확인으로 뒤집혀, 실제로
 * 확인한 사실을 잃는다.
 *
 * <p>지금 실제로 차이를 만드는 갈래는 둘이다. 하나는 계단 경고({@link #ofWarnings(List)})다 —
 * V1은 고신뢰 출구 중 쓸 수 있는 무단차 출구를 찾지 못하면 계단 구간을 관측하지 않고도
 * {@code STAIR_ONLY_ACCESS}를 붙이므로({@code RouteSearchService.hasStairOnlyAccess}), 스텝을
 * 접은 값이 {@link #STEP_FREE}여도 경로 판정은 {@link #STAIR_ONLY}가 된다. 다른 하나는 스텝이
 * 하나도 없는 차단 결과이며, 그 차이는 {@link #ofItineraryDisplay}의 빈 목록 규칙이 만든다.
 *
 * <p>데이터 신뢰도 축({@link #demotedIfUnverified(boolean)})은 <b>현재 생산자에서 발화하지
 * 않는다.</b> RAPTOR의 경로 경고는 지나온 전이들의 warningCodes를 OR한 것이고 LOW/STALE 비트를
 * 낸 전이는 정의상 미검증이라 그 스텝이 이미 {@link #UNKNOWN}이며, V1의 접근 스텝은
 * {@code requiresAccessibilityCheck}가 상수 {@code true}다. 그래도 겹쳐 두는 이유는 fail closed
 * 하나뿐이다 — 이 축이 오늘 판정을 낮추고 있다고 읽어서는 안 된다.
 *
 * <p>표시 계층은 경로 판정이 응답에 실려 있으면 그 값을 쓴다. leg를 접어 경로 판정을 흉내
 * 내는 것은 판정 필드가 없는 응답에서의 폴백이며, 그 응답에서는 승차 leg의 미확인 원자료가
 * 폴백을 fail closed로 떨어뜨린다.
 */
public enum StairAccess {

	/**
	 * 계단 개념이 적용되지 않는 구간. 승차 구간에는 오르내릴 계단 자체가 없으므로 미확인이
	 * 아니다. 경로 판정에 기여하지 않는다.
	 */
	NOT_APPLICABLE(0),
	STEP_FREE(1),
	UNKNOWN(2),
	STAIR_ONLY(3);

	private static final String RIDE_STEP_TYPE = "ride";

	/**
	 * 정직 사다리에서의 신중함 등급. 값을 하나 추가할 때 선언 위치에 따라 판정이 조용히
	 * 뒤집히지 않도록 등급을 값에 못박는다.
	 */
	private final int caution;

	StairAccess(int caution) {
		this.caution = caution;
	}

	/**
	 * 스텝 하나의 계단 판정. 입력은 계단 사실뿐이다 — 데이터 신뢰도는 별도 축이라
	 * {@link #demotedIfUnverified(boolean)}가 맡는다.
	 */
	public static StairAccess ofStep(RouteStep step) {
		if (step.includesStairs()) {
			return STAIR_ONLY;
		}
		if (step.requiresAccessibilityCheck()) {
			return UNKNOWN;
		}
		return RIDE_STEP_TYPE.equals(step.stepType()) ? NOT_APPLICABLE : STEP_FREE;
	}

	/**
	 * 경로 전체의 계단 판정. #2560 무단차 대안 태깅이 쓰는 술어이므로 계단 사실만 본다 —
	 * 데이터 신뢰도 경고로 후보 집합이 흔들리면 안 된다.
	 */
	public static StairAccess ofItinerary(RouteSearchResult itinerary) {
		return ofStepJudgments(itinerary.steps().stream().map(StairAccess::ofStep).toList())
			.merge(ofWarnings(itinerary.warnings()));
	}

	/**
	 * 응답에 실어 화면이 표시할 경로 판정. 태깅 술어({@link #ofItinerary})에 데이터 신뢰도 축을
	 * 겹친 값이다 — 후보 집합은 계단 사실만으로 정해야 하지만(#2560), 사용자에게 말할 때는
	 * "확인하지 못했다"를 감출 수 없다. 다만 그 축은 클래스 주석에 적은 대로 현재 생산자에서
	 * 발화하지 않으므로, 오늘 {@link #ofItinerary}와 갈리는 갈래는 아래 빈 목록 규칙뿐이다.
	 *
	 * <p>스텝이 하나도 없으면(접근성 차단 결과) 무단차라 말할 근거 자체가 없으므로 접는 값을
	 * {@link #UNKNOWN}으로 둔다. 계단 경고는 그때도 경로 단위 사실이라 그대로 겹친다.
	 * {@link #ofStepJudgments(List)}의 빈 목록 결론은 #2560 후보 집합을 흔들지 않도록 종전
	 * 그대로 뒀다.
	 */
	public static StairAccess ofItineraryDisplay(RouteSearchResult itinerary) {
		StairAccess folded = itinerary.steps().isEmpty()
			? UNKNOWN
			: ofStepJudgments(itinerary.steps().stream().map(StairAccess::ofStep).toList());
		return folded
			.merge(ofWarnings(itinerary.warnings()))
			.demotedIfUnverified(hasUnverifiedEvidence(itinerary.warnings()));
	}

	/**
	 * 스텝 판정들을 경로 판정으로 접는다. 모든 스텝이 {@link #NOT_APPLICABLE}이면 계단
	 * 장벽이 놓인 구간이 하나도 없다는 뜻이므로 {@link #STEP_FREE}로 확정한다.
	 */
	public static StairAccess ofStepJudgments(List<StairAccess> stepJudgments) {
		StairAccess merged = stepJudgments.stream().reduce(NOT_APPLICABLE, StairAccess::merge);
		return merged == NOT_APPLICABLE ? STEP_FREE : merged;
	}

	/**
	 * 경로 전체에 걸린 계단 경고. 특정 구간에 매달 수 없어 leg 판정이 담지 못하는 신호다.
	 */
	public static StairAccess ofWarnings(List<RouteWarning> warnings) {
		return warnings.stream().anyMatch(warning -> warning.code() == RouteWarningCode.STAIR_ONLY_ACCESS)
			? STAIR_ONLY
			: NOT_APPLICABLE;
	}

	/**
	 * 계단 사실이 아니라 "그 사실을 확인할 수 없었다"를 말하는 경고가 붙었는지.
	 *
	 * <p>분류를 {@code switch}로 두는 이유는 fail open을 막기 위해서다. {@link RouteWarningCode}에
	 * 값이 추가되면 이 exhaustive switch가 컴파일을 멈춰 분류를 강제한다. 카운터 필드를 읽는
	 * 방식이었다면 새 사유가 조용히 0으로 남아 무단차 단언을 통과시켰을 것이다.
	 */
	public static boolean hasUnverifiedEvidence(List<RouteWarning> warnings) {
		return warnings.stream().anyMatch(warning -> switch (warning.code()) {
			case LOW_DATA_CONFIDENCE, STALE_ACCESSIBILITY_DATA -> true;
			case STAIR_ONLY_ACCESS -> false;
		});
	}

	/**
	 * 확인되지 않은 근거가 붙었으면 무단차 단언을 거둔다(정직 사다리). 계단이 있다는 판정은
	 * 신뢰도와 축이 다른 사실이라 흔들지 않는다.
	 *
	 * <p>현재 생산자에서는 이 강등이 일어나지 않는다(클래스 주석 참고). 순수 함수로 두고
	 * 단위 테스트로 고정해 두는 것은 앞으로 검증된 스텝과 신뢰도 경고가 함께 오는 생산자가
	 * 생겼을 때 판정이 조용히 열리지 않게 하기 위해서다.
	 */
	public StairAccess demotedIfUnverified(boolean unverifiedEvidence) {
		return this == STEP_FREE && unverifiedEvidence ? UNKNOWN : this;
	}

	/** 둘 중 더 신중한 판정. */
	public StairAccess merge(StairAccess other) {
		return caution >= other.caution ? this : other;
	}
}
