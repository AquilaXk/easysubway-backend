package com.easysubway.operator.adapter.in.web;

import java.util.List;

/**
 * 운영기관 리포트 CSV 직렬화 공용 도우미(#1748).
 *
 * <p>엑셀 한글 호환을 위해 UTF-8 BOM으로 시작하고 RFC 4180 방식으로 필드를 감싼다.
 * 신고 메시지·역명 등 사용자 유래 값이 셀에 들어가므로 수식 인젝션(CWE-1236)을 막기 위해
 * 수식 문자(= + - @, 탭·CR)로 시작하는 값 앞에는 작은따옴표를 덧붙인다.
 */
final class OperatorReportCsv {

	private static final String UTF8_BOM = "﻿";

	private OperatorReportCsv() {
	}

	static String document(List<String> header, List<List<String>> rows) {
		StringBuilder csv = new StringBuilder(UTF8_BOM);
		appendLine(csv, header);
		for (List<String> row : rows) {
			appendLine(csv, row);
		}
		return csv.toString();
	}

	private static void appendLine(StringBuilder csv, List<String> cells) {
		for (int i = 0; i < cells.size(); i++) {
			if (i > 0) {
				csv.append(',');
			}
			csv.append(field(cells.get(i)));
		}
		csv.append("\r\n");
	}

	static String field(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		String guarded = value;
		char first = value.charAt(0);
		if (first == '=' || first == '+' || first == '-' || first == '@'
			|| first == '\t' || first == '\r' || first == '\n') {
			guarded = "'" + value;
		}
		if (guarded.indexOf(',') >= 0
			|| guarded.indexOf('"') >= 0
			|| guarded.indexOf('\n') >= 0
			|| guarded.indexOf('\r') >= 0) {
			return '"' + guarded.replace("\"", "\"\"") + '"';
		}
		return guarded;
	}
}
