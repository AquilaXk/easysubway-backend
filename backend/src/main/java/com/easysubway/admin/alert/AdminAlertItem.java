package com.easysubway.admin.alert;

/**
 * 알림 센터의 개별 항목(#1738). 하나의 운영 신호를 요약해 해당 화면으로 딥링크한다.
 *
 * @param id    신호 식별자(report-surge·push-failure·batch-failure·datapack-blocker)
 * @param label 벨 요약 패널에 보이는 제목
 * @param detail 건수 등 부가 설명
 * @param tone  상태 톤(warning·failure) — 스타일과 aria 톤에 쓴다
 * @param href  신호를 확인·처리할 화면 딥링크
 */
public record AdminAlertItem(String id, String label, String detail, String tone, String href) {
}
