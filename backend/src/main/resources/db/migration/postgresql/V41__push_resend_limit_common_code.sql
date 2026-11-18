-- 푸시 재발송 1회 상한(#1746): 대량 오발송 방지를 위해 공통코드로 관리한다.
-- 상한 값은 code 문자열에 숫자로 담고, 관리자 공통코드 콘솔에서 변경한다(비활성 후 새 값 추가).
INSERT INTO admin_common_code_groups (group_code, display_name, description, sort_order, enabled, created_at, updated_at)
VALUES ('PUSH_RESEND_LIMIT', '푸시 재발송 상한', '1회 재발송으로 처리할 수 있는 최대 건수', 70, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO admin_common_codes (group_code, code, display_name, description, sort_order, enabled, created_at, updated_at)
VALUES ('PUSH_RESEND_LIMIT', '50', '1회 재발송 상한(건)', '한 번에 재발송할 수 있는 최대 실패 건수', 10, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
