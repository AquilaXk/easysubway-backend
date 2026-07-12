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

	var PALETTE = [
		tokenColor('--admin-good', '#0a705a'),
		tokenColor('--admin-danger', '#b42318'),
		tokenColor('--admin-chart-series', '#2f6f9f'),
		tokenColor('--admin-warn', '#9a5600')
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
