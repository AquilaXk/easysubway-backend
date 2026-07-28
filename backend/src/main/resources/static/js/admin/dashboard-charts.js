// 통합 대시보드 추이 차트(#1739). canvas.trend-canvas의 data-chart(JSON)를 읽어 Chart.js
// 라인 차트를 그린다. 자체 호스팅 파일이라 CSP(script-src 'self')를 지킨다(인라인 없음).
// 기간 버튼이 htmx로 #dashboard-trends를 부분 갱신하면 새 canvas가 들어오므로 afterSwap에서
// 다시 그린다. Chart.js가 없거나 JSON이 없으면 조용히 넘어가고 details 안 데이터 표가 대체한다.
(function () {
	function tokenColor(name) {
		if (typeof getComputedStyle !== 'function') {
			return '';
		}
		return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
	}

	// 팔레트(#1983): 무채색(잉크 계열) + 파랑 액센트 + 상태 3색만 사용한다. 장식적 초록·빨강
	// 조합 대신 1번 시리즈는 파랑(주 지표), 2번은 잉크 톤(보조 지표)으로 구분한다.
	var PALETTE = [
		tokenColor('--admin-accent'),
		tokenColor('--admin-ink-2'),
		tokenColor('--admin-ink-3'),
		tokenColor('--admin-chart-series')
	];

	// #2281 V6-09: 차트를 그리지 못하면(Chart.js 부재·JSON 파싱 실패) 같은 값의 대체 표
	// (details.dashboard-details "데이터 표로 보기")를 펼쳐 동일 데이터를 즉시 보이게 한다.
	// 표 값은 서버가 canvas data-chart와 같은 소스로 렌더해 chart와 항상 일치한다.
	function revealFallbackTable(canvas) {
		var section = canvas.closest('section');
		var fallback = section && section.querySelector('details.dashboard-details');
		if (fallback) {
			fallback.open = true;
		}
	}

	function renderChart(canvas) {
		if (!window.Chart) {
			revealFallbackTable(canvas);
			return;
		}
		var raw = canvas.getAttribute('data-chart');
		if (!raw) {
			revealFallbackTable(canvas);
			return;
		}
		var data;
		try {
			data = JSON.parse(raw);
		} catch (error) {
			revealFallbackTable(canvas);
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

	// #2281 V6-09: 지표 수동 재집계 중복 실행 방지. snapshotToday()는 멱등이지만 더블클릭이 중복 POST를
	// 낸다. 첫 제출에서 버튼을 비활성화(자리·크기 그대로 두어 layout stability 유지, aria-busy로 진행 표시)해
	// 중복 실행을 막는다. no-JS에서는 이 가드 없이 폼이 그대로 한 번 제출된다.
	function guardSnapshotRerun() {
		var form = document.querySelector('[data-dashboard-snapshot-form]');
		if (!form) {
			return;
		}
		form.addEventListener('submit', function () {
			if (form.getAttribute('data-submitting') === 'true') {
				return;
			}
			form.setAttribute('data-submitting', 'true');
			var button = form.querySelector('button[type="submit"]');
			if (button) {
				button.setAttribute('aria-busy', 'true');
				button.disabled = true;
			}
		});
	}

	document.addEventListener('DOMContentLoaded', function () {
		renderAll(document);
		guardSnapshotRerun();
	});
	document.body.addEventListener('htmx:afterSwap', function (event) {
		renderAll(event.target);
	});
})();
