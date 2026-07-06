package com.easysubway.common.web.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("eGovFrame fdl-excel xlsx 내보내기 지원")
class EgovExcelExportSupportTest {

	private final EgovExcelExportSupport support = new EgovExcelExportSupport();

	@Test
	@DisplayName("헤더+행을 열어서 읽을 수 있는 xlsx로 렌더한다")
	void rendersReadableXlsx() throws Exception {
		byte[] xlsx = support.toXlsx(
			"export",
			List.of("colA", "colB"),
			List.of(List.of("a1", "b1"), List.of("a2", "b2"))
		);

		try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getSheetName()).isEqualTo("export");
			assertThat(cell(sheet, 0, 0)).isEqualTo("colA");
			assertThat(cell(sheet, 0, 1)).isEqualTo("colB");
			assertThat(cell(sheet, 1, 0)).isEqualTo("a1");
			assertThat(cell(sheet, 2, 1)).isEqualTo("b2");
			assertThat(sheet.getLastRowNum()).isEqualTo(2);
		}
	}

	@Test
	@DisplayName("1만 행 내보내기는 힙 증분 상한 안에서 완료된다(메모리 방어 증거)")
	void bounds10kRowExportMemory() throws Exception {
		List<List<String>> rows = new ArrayList<>();
		for (int index = 0; index < 10_000; index++) {
			rows.add(List.of("station-" + index, "facility-" + index, String.valueOf(index), "detail-" + index));
		}

		System.gc();
		long before = usedHeapBytes();
		byte[] xlsx = support.toXlsx("large", List.of("a", "b", "c", "d"), rows);
		long increment = usedHeapBytes() - before;

		try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(10_000);
		}
		// in-memory XSSF 10k행×4열의 힙 증분은 넉넉한 상한(256MB) 안에 머문다.
		assertThat(increment).isLessThan(256L * 1024 * 1024);
	}

	@Test
	@DisplayName("행 수 상한을 초과하면 발번을 거부한다(상한 초과 시 실패)")
	void rejectsRowsBeyondLimit() {
		List<List<String>> rows = new ArrayList<>();
		for (int index = 0; index < EgovExcelExportSupport.MAX_EXPORT_ROWS + 1; index++) {
			rows.add(List.of(String.valueOf(index)));
		}

		assertThatThrownBy(() -> support.toXlsx("overflow", List.of("a"), rows))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(String.valueOf(EgovExcelExportSupport.MAX_EXPORT_ROWS));
	}

	private static String cell(Sheet sheet, int rowIndex, int columnIndex) {
		Row row = sheet.getRow(rowIndex);
		return row == null ? null : row.getCell(columnIndex).getStringCellValue();
	}

	private static long usedHeapBytes() {
		Runtime runtime = Runtime.getRuntime();
		return runtime.totalMemory() - runtime.freeMemory();
	}
}
