package com.easysubway.journey.bundle;

/** 컴파일된 경로 번들이 담은 전환-시설 요구 매핑(#418). 표가 없는 번들은 {@link TransitionFacilityRequirements#missing()}이다. */
public interface TransitionFacilityRequirementSource {

	TransitionFacilityRequirements transitionFacilityRequirements();
}
