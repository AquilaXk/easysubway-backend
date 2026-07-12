// Operator report charts read the adjacent fallback table, so no inline JSON is needed.
(function () {
	function tokenColor(name, fallback) {
		if (typeof getComputedStyle !== 'function') {
			return fallback;
		}
		var value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
		return value || fallback;
	}

	var COLORS = [
		tokenColor('--admin-good', '#0a705a'),
		tokenColor('--admin-danger', '#b42318'),
		tokenColor('--admin-chart-series', '#2f6f9f'),
		tokenColor('--admin-warn', '#9a5600')
	];

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
		canvas.chartInstance = new window.Chart(canvas, {
			type: type,
			data: {
				labels: data.labels,
				datasets: [{
					label: '건수',
					data: data.values,
					backgroundColor: COLORS,
					borderColor: COLORS
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
