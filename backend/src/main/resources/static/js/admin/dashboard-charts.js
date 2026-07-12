// 통합 대시보드 추이 차트(#1739). canvas.trend-canvas의 data-chart(JSON)를 읽어 Chart.js
// 라인 차트를 그린다. 자체 호스팅 파일이라 CSP(script-src 'self')를 지킨다(인라인 없음).
// 기간 버튼이 htmx로 #dashboard-trends를 부분 갱신하면 새 canvas가 들어오므로 afterSwap에서
// 다시 그린다. Chart.js가 없거나 JSON이 없으면 조용히 넘어가고 details 안 데이터 표가 대체한다.
(function () {
	function tokenColor(name, fallback) {
		if (typeof getComputedStyle !== 'function') {
			return fallback;
		}
		var value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
		return value || fallback;
	}

	// 팔레트(#1983): 무채색(잉크 계열) + 파랑 액센트 + 상태 3색만 사용한다. 장식적 초록·빨강
	// 조합 대신 1번 시리즈는 파랑(주 지표), 2번은 잉크 톤(보조 지표)으로 구분한다.
	var PALETTE = [
		tokenColor('--admin-accent', '#006fd6'),
		tokenColor('--admin-ink-2', '#29484b'),
		tokenColor('--admin-ink-3', '#466467'),
		tokenColor('--admin-danger', '#b42318')
	];

	function renderChart(canvas) {
		if (!window.Chart) {
			return;
		}
		var raw = canvas.getAttribute('data-chart');
		if (!raw) {
			return;
		}
		var data;
		try {
			data = JSON.parse(raw);
		} catch (error) {
			return;
		}
		if (canvas.chartInstance) {
			canvas.chartInstance.destroy();
		}
		var datasets = (data.series || []).map(function (series, index) {
			var color = PALETTE[index % PALETTE.length];
			return {
				label: series.label,
				data: series.values,
				borderColor: color,
				backgroundColor: color,
				spanGaps: false,
				tension: 0.25,
				pointRadius: 2
			};
		});
		canvas.chartInstance = new window.Chart(canvas, {
			type: 'line',
			data: { labels: data.labels || [], datasets: datasets },
			options: {
				responsive: true,
				maintainAspectRatio: false,
				interaction: { mode: 'index', intersect: false },
				plugins: { legend: { position: 'bottom' } },
				scales: { y: { beginAtZero: true } }
			}
		});
	}

	function renderAll(root) {
		var scope = root && root.querySelectorAll ? root : document;
		scope.querySelectorAll('canvas.trend-canvas').forEach(renderChart);
	}

	document.addEventListener('DOMContentLoaded', function () {
		renderAll(document);
	});
	document.body.addEventListener('htmx:afterSwap', function (event) {
		renderAll(event.target);
	});
})();
