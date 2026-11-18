UPDATE admin_menu_items
SET display_name = '제보 확인 대기열'
WHERE program_code = 'a-reports';

UPDATE admin_menu_items
SET display_name = '현장 확인'
WHERE program_code = 'a-field-verifications';

UPDATE admin_common_code_groups
SET description = '제보 확인에서 반복 선택하는 반려 사유'
WHERE group_code = 'REPORT_REJECTION_REASON';

UPDATE admin_common_codes
SET description = '제보 확인 후 상태 변경'
WHERE group_code = 'FACILITY_STATUS_REASON'
  AND code = 'REPORT_CONFIRMED';

UPDATE admin_common_codes
SET display_name = '확인 실패',
    description = '수집 산출물 확인 실패'
WHERE group_code = 'BATCH_FAILURE_CATEGORY'
  AND code = 'VALIDATION_ERROR';
