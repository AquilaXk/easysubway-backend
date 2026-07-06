package com.easysubway.common.web.export;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.egovframe.rte.fdl.excel.util.AbstractPOIExcelView;
import org.springframework.stereotype.Component;

/**
 * 관리자·운영기관 내보내기용 공용 xlsx 지원 클래스.
 *
 * <p>eGovFrame fdl-excel의 {@link AbstractPOIExcelView}(POI XSSF 기반 Excel 렌더)를 확장해
 * 헤더+행 데이터를 xlsx 바이트로 변환한다. eGov excel 타입은 이 파일에만 둔다(컨트롤러는 이 지원
 * 클래스만 경유, eGov 직접 import 금지). 셀 값은 호출부가 각자의 CSV와 동일한 데이터 정책으로
 * 구성해 넘긴다(동일 데이터·동일 노출 정책 유지).
 *
 * <p>내보내기는 도메인상 소규모(역별 확인 항목/제안 행)이므로 in-memory XSSF로 충분하며, 예기치
 * 못한 대량 요청은 {@link #MAX_EXPORT_ROWS} 상한으로 조기 차단한다(메모리 방어 증거).
 */
@Component
public class EgovExcelExportSupport extends AbstractPOIExcelView {

	/** in-memory 렌더 방어 상한. 초과 시 발번을 거부한다(상한 초과 시 실패). */
	public static final int MAX_EXPORT_ROWS = 50_000;

	/**
	 * 헤더 1행 + 데이터 행들을 하나의 시트로 렌더해 xlsx 바이트를 반환한다.
	 *
	 * @param sheetName 시트 이름
	 * @param header 헤더 셀 값
	 * @param rows 행별 셀 값(호출부 CSV와 동일 데이터 정책으로 이미 구성됨)
	 * @return xlsx 바이트
	 */
	public byte[] toXlsx(String sheetName, List<String> header, List<List<String>> rows) {
		if (rows.size() > MAX_EXPORT_ROWS) {
			throw new IllegalArgumentException(
				"내보내기 행 수 상한(" + MAX_EXPORT_ROWS + ")을 초과했습니다: " + rows.size());
		}
		try (XSSFWorkbook workbook = new XSSFWorkbook();
			ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			writeSheet(workbook, sheetName, header, rows);
			workbook.write(out);
			return out.toByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException("xlsx 내보내기 생성에 실패했습니다.", exception);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	protected void buildExcelDocument(
		Map<String, Object> model,
		XSSFWorkbook workbook,
		HttpServletRequest request,
		HttpServletResponse response
	) {
		writeSheet(
			workbook,
			(String) model.getOrDefault("sheetName", "export"),
			(List<String>) model.getOrDefault("header", List.of()),
			(List<List<String>>) model.getOrDefault("rows", List.of())
		);
	}

	private void writeSheet(XSSFWorkbook workbook, String sheetName, List<String> header, List<List<String>> rows) {
		XSSFSheet sheet = workbook.createSheet(sheetName);
		for (int column = 0; column < header.size(); column++) {
			setText(getCell(sheet, 0, column), header.get(column));
		}
		for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
			List<String> row = rows.get(rowIndex);
			for (int column = 0; column < row.size(); column++) {
				String value = row.get(column);
				setText(getCell(sheet, rowIndex + 1, column), value == null ? "" : value);
			}
		}
	}
}
