// Operator report charts read the adjacent fallback table, so no inline JSON is needed.
(function () {
	function tokenColor(name, fallback) {
		if (typeof getComputedStyle !== 'function') {
			return fallback;
		}
		var value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
		return value || fallback;
	}

	// 색 매핑(#2349 PR⑩e 리뷰 반영): dashboard-charts.js PALETTE처럼 인덱스 순서로 색을 배정하면
	// 카테고리 라벨과 상태 의미가 우연히 뒤바뀐다(예: "발송 실패"가 3번째 행이면 경고색이 아니라
	// 잉크 톤을 받는다). 라벨 문자열을 직접 조회해 의미를 고정한다 — 운영 워크플로 상태
	// (완료/실패/대기 중/실행 중)만 admin 상태 문법의 good/danger 톤을 쓰고, 그 외(품질 등급
	// LEVEL_1~4, 시설별 반복 신고 건수, 피드백 평점 등 상태 의미가 없는 분포)는 중립 파랑·잉크
	// 시퀀스를 순환한다. 새 라벨이 추가돼도 여기 표에 없으면 자동으로 중립 시퀀스로 fallback된다.
	var GOOD_LABELS = ['완료', '발송 완료'];
	var DANGER_LABELS = ['실패', '발송 실패'];
	var NEUTRAL_SEQUENCE = [
		tokenColor('--admin-accent', '#006fd6'),
		tokenColor('--admin-ink-2', '#29484b'),
		tokenColor('--admin-ink-3', '#466467'),
		tokenColor('--admin-chart-series', '#2f6f9f')
	];

	function colorsForLabels(labels) {
		var goodColor = tokenColor('--admin-good', '#0a705a');
		var dangerColor = tokenColor('--admin-danger', '#b42318');
		var neutralIndex = 0;
		return labels.map(function (label) {
			if (GOOD_LABELS.indexOf(label) !== -1) {
				return goodColor;
			}
			if (DANGER_LABELS.indexOf(label) !== -1) {
				return dangerColor;
			}
			var color = NEUTRAL_SEQUENCE[neutralIndex % NEUTRAL_SEQUENCE.length];
			neutralIndex += 1;
			return color;
		});
	}

	function tableData(canvas) {
		var tableId = canvas.getAttribute('data-operator-chart-table');
		var table = tableId ? document.getElementById(tableId) : null;
		if (!table) {
			return null;
		}
		var labels = [];
		var values = [];
		table.querySelectorAll('tbody tr').forEach(function (row) {
			var cells = row.querySelectorAll('td');
			if (cells.length < 2) {
				return;
			}
			labels.push(cells[0].textContent.trim());
			values.push(Number(cells[1].textContent.trim().replace(/,/g, '')) || 0);
		});
		return { labels: labels, values: values };
	}

	function render(canvas) {
		if (!window.Chart) {
			return;
		}
		var data = tableData(canvas);
		if (!data || data.labels.length === 0) {
			return;
		}
		if (canvas.chartInstance) {
			canvas.chartInstance.destroy();
		}
		var type = canvas.getAttribute('data-operator-chart') || 'bar';
		var colors = colorsForLabels(data.labels);
		canvas.chartInstance = new window.Chart(canvas, {
			type: type,
			data: {
				labels: data.labels,
				datasets: [{
					label: '건수',
					data: data.values,
					backgroundColor: colors,
					borderColor: colors
				}]
			},
			options: {
				responsive: true,
				maintainAspectRatio: false,
				plugins: { legend: { display: type !== 'bar', position: 'bottom' } },
				scales: type === 'bar' ? { y: { beginAtZero: true } } : {}
			}
		});
	}

	document.addEventListener('DOMContentLoaded', function () {
		document.querySelectorAll('canvas[data-operator-chart]').forEach(render);
	});
})();
