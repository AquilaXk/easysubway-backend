// 배치 잡별 실행 이력 막대 차트(#1742). canvas.batch-history-canvas의 data-chart(JSON)를 읽어
// Chart.js 막대 차트를 그린다. 자체 호스팅 파일이라 CSP(script-src 'self')를 지킨다(인라인 없음).
// values=소요 ms, statuses=실행 상태로 막대 색을 성공/실패/실행 중으로 구분한다.
// htmx 부분 갱신(자동 갱신 폴링)으로 새 canvas가 들어오면 afterSwap에서 다시 그린다.
// Chart.js가 없거나 JSON이 없으면 조용히 넘어가고 details 안 데이터 표가 대체한다.
(function () {
	var COLORS = { COMPLETED: '#0f6b52', FAILED: '#b3402c', RUNNING: '#8a5a00' };

	function barColor(status) {
		return COLORS[status] || '#2f6f9f';
	}

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
		var statuses = data.statuses || [];
		var colors = (data.values || []).map(function (value, index) {
			return barColor(statuses[index]);
		});
		canvas.chartInstance = new window.Chart(canvas, {
			type: 'bar',
			data: {
				labels: data.labels || [],
				datasets: [{
					label: '소요(ms)',
					data: data.values || [],
					backgroundColor: colors,
					borderColor: colors
				}]
			},
			options: {
				responsive: true,
				maintainAspectRatio: false,
				plugins: { legend: { display: false } },
				scales: { y: { beginAtZero: true } }
			}
		});
	}

	function renderAll(root) {
		var scope = root && root.querySelectorAll ? root : document;
		scope.querySelectorAll('canvas.batch-history-canvas').forEach(renderChart);
	}

	document.addEventListener('DOMContentLoaded', function () {
		renderAll(document);
	});
	document.body.addEventListener('htmx:afterSwap', function (event) {
		renderAll(event.target);
	});
})();
