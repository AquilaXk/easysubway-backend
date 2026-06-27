package com.easysubway.admin.code.adapter.out.persistence;

import com.easysubway.admin.code.application.port.out.AdminCommonCodeRepository;
import com.easysubway.admin.code.domain.AdminCommonCode;
import com.easysubway.admin.code.domain.AdminCommonCodeGroup;
import com.easysubway.admin.code.domain.AdminCommonCodeGroups;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
public class InMemoryAdminCommonCodeRepository implements AdminCommonCodeRepository {

	private final Map<String, AdminCommonCodeGroup> groups = new LinkedHashMap<>();
	private final Map<String, AdminCommonCode> codes = new LinkedHashMap<>();

	public InMemoryAdminCommonCodeRepository() {
		LocalDateTime now = LocalDateTime.of(2026, 6, 27, 0, 0);
		seedGroup(AdminCommonCodeGroups.REPORT_REJECTION_REASON, "신고 반려 사유", "제보 검수에서 반복 선택하는 반려 사유", 10, now);
		seedGroup(AdminCommonCodeGroups.FACILITY_STATUS_REASON, "시설 변경 사유", "시설 상태 변경 사유", 20, now);
		seedGroup(AdminCommonCodeGroups.BATCH_FAILURE_CATEGORY, "배치 실패 분류", "수집 배치 실패 원인 분류", 30, now);
		seedGroup(AdminCommonCodeGroups.INCIDENT_SEVERITY, "장애 심각도", "운영 incident 심각도", 40, now);
		seedGroup(AdminCommonCodeGroups.INCIDENT_STATUS, "장애 상태", "운영 incident 처리 상태", 50, now);
		seedGroup(AdminCommonCodeGroups.INCIDENT_SOURCE, "장애 출처", "incident 발생 출처", 60, now);
		seedCode(AdminCommonCodeGroups.REPORT_REJECTION_REASON, "DUPLICATE", "중복 제보", "이미 처리 중인 동일 제보", 10, true, now);
		seedCode(AdminCommonCodeGroups.REPORT_REJECTION_REASON, "INSUFFICIENT", "정보 부족", "역·시설·사진 정보 부족", 20, true, now);
		seedCode(AdminCommonCodeGroups.FACILITY_STATUS_REASON, "INSPECTION", "정기 점검", "운영기관 정기 점검", 10, true, now);
		seedCode(AdminCommonCodeGroups.FACILITY_STATUS_REASON, "REPORT_CONFIRMED", "제보 확인", "제보 검수 후 상태 변경", 20, true, now);
		seedCode(AdminCommonCodeGroups.BATCH_FAILURE_CATEGORY, "SOURCE_TIMEOUT", "원천 응답 지연", "원천 데이터 응답 시간 초과", 10, true, now);
		seedCode(AdminCommonCodeGroups.BATCH_FAILURE_CATEGORY, "VALIDATION_ERROR", "검증 실패", "수집 산출물 검증 실패", 20, true, now);
		seedCode(AdminCommonCodeGroups.INCIDENT_SEVERITY, "MAJOR", "Major", "사용자 기능 영향", 10, true, now);
		seedCode(AdminCommonCodeGroups.INCIDENT_SEVERITY, "MINOR", "Minor", "운영 확인 필요", 20, true, now);
		seedCode(AdminCommonCodeGroups.INCIDENT_STATUS, "OPEN", "Open", "처리 전", 10, true, now);
		seedCode(AdminCommonCodeGroups.INCIDENT_STATUS, "RESOLVED", "Resolved", "해결됨", 20, true, now);
		seedCode(AdminCommonCodeGroups.INCIDENT_SOURCE, "HEALTH", "Health", "health 상태", 10, true, now);
		seedCode(AdminCommonCodeGroups.INCIDENT_SOURCE, "BATCH", "Batch", "배치 실행", 20, true, now);
	}

	@Override
	public synchronized List<AdminCommonCodeGroup> findGroups() {
		return groups.values().stream()
			.sorted(Comparator.comparingInt(AdminCommonCodeGroup::sortOrder).thenComparing(AdminCommonCodeGroup::groupCode))
			.toList();
	}

	@Override
	public synchronized Optional<AdminCommonCodeGroup> findGroup(String groupCode) {
		return Optional.ofNullable(groups.get(groupCode));
	}

	@Override
	public synchronized List<AdminCommonCode> findCodes(String groupCode) {
		return new ArrayList<>(codes.values()).stream()
			.filter(code -> code.groupCode().equals(groupCode))
			.sorted(Comparator.comparingInt(AdminCommonCode::sortOrder).thenComparing(AdminCommonCode::code))
			.toList();
	}

	@Override
	public synchronized Optional<AdminCommonCode> findCode(String groupCode, String code) {
		return Optional.ofNullable(codes.get(key(groupCode, code)));
	}

	@Override
	public synchronized AdminCommonCode saveCode(AdminCommonCode code) {
		codes.put(key(code.groupCode(), code.code()), code);
		return code;
	}

	private void seedGroup(String groupCode, String displayName, String description, int sortOrder, LocalDateTime now) {
		groups.put(groupCode, new AdminCommonCodeGroup(groupCode, displayName, description, sortOrder, true, now, now));
	}

	private void seedCode(
		String groupCode,
		String code,
		String displayName,
		String description,
		int sortOrder,
		boolean enabled,
		LocalDateTime now
	) {
		codes.put(key(groupCode, code), new AdminCommonCode(groupCode, code, displayName, description, sortOrder, enabled, now, now));
	}

	private static String key(String groupCode, String code) {
		return groupCode + ":" + code;
	}
}
