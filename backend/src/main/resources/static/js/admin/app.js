// 관리자 콘솔 Alpine.js(CSP 빌드) 컴포넌트 등록 진입점.
//
// CSP 규약(#1736):
//   - CSP 빌드는 인라인 표현식을 평가하지 않는다. 모든 로직은 여기서 Alpine.data()로
//     "명명된 컴포넌트"를 등록하고, 템플릿은 x-data="컴포넌트명"으로 참조만 한다.
//   - x-on:click 등 디렉티브 값도 컴포넌트의 "메서드/프로퍼티 이름"만 허용된다(표현식 금지).
//   - 인라인 <script>·on*= 핸들러는 금지(script-src 'self').
//
// 진화형 향상(progressive enhancement) 원칙:
//   - JS가 꺼져도 화면은 온전히 동작한다. 여기 등록되는 컴포넌트는 "이미 동작하는 화면에
//     선택적 편의"만 얹는다(예: 이미 보이는 알림을 닫는 버튼).
// JS 사용 가능 표식: 반응형 사이드바 오프캔버스는 JS가 있을 때만 켠다(no-JS는 스택 표시).
// app.js는 defer라 이 시점에 document.body가 존재한다.
document.body.classList.add('has-js');

// 오버레이 포커스 트랩(#2416): 드로어·커맨드 팔레트·알림 패널이 Tab/Shift+Tab을 순환하고
// 닫을 때 트리거로 포커스를 복원한다. CSP 빌드 Alpine은 표현식을 쓰지 않으므로 공유 헬퍼로 둔다.
function adminFocusableElements(root) {
	if (!root) {
		return [];
	}
	var nodes = root.querySelectorAll(
		'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
	);
	return Array.prototype.filter.call(nodes, function (el) {
		return !el.hasAttribute('disabled') && el.getAttribute('aria-hidden') !== 'true';
	});
}

function adminTrapTabKey(event, root) {
	if (event.key !== 'Tab' || !root) {
		return;
	}
	var focusable = adminFocusableElements(root);
	if (!focusable.length) {
		event.preventDefault();
		return;
	}
	var first = focusable[0];
	var last = focusable[focusable.length - 1];
	if (event.shiftKey) {
		if (document.activeElement === first || !root.contains(document.activeElement)) {
			event.preventDefault();
			last.focus();
		}
	} else if (document.activeElement === last || !root.contains(document.activeElement)) {
		event.preventDefault();
		first.focus();
	}
}

function adminFocusFirst(root) {
	var focusable = adminFocusableElements(root);
	if (focusable.length) {
		focusable[0].focus();
	} else if (root && root.focus) {
		root.focus();
	}
}

function adminReturnFocus(state, fallbackEl) {
	var target = state.returnFocus || fallbackEl;
	state.returnFocus = null;
	if (target && target.focus) {
		target.focus();
	}
}

// htmx 전역 피드백(#2416): topbar 인디케이터·실패 토스트. no-JS 폴백(form GET·링크)은 그대로다.
(function initHtmxFeedback() {
	var indicator = document.getElementById('admin-htmx-indicator');
	var pendingRequests = 0;

	function setIndicatorBusy(isBusy) {
		if (!indicator) {
			return;
		}
		indicator.setAttribute('aria-busy', isBusy ? 'true' : 'false');
	}

	function beginHtmxRequest() {
		pendingRequests += 1;
		setIndicatorBusy(true);
	}

	function endHtmxRequest() {
		pendingRequests = Math.max(0, pendingRequests - 1);
		if (pendingRequests === 0) {
			setIndicatorBusy(false);
		}
	}

	if (indicator) {
		setIndicatorBusy(false);
		if (window.htmx) {
			document.body.setAttribute('hx-indicator', '#admin-htmx-indicator');
			window.htmx.process(document.body);
		}
		document.body.addEventListener('htmx:beforeRequest', beginHtmxRequest);
		document.body.addEventListener('htmx:afterRequest', endHtmxRequest);
	}
	document.body.addEventListener('htmx:responseError', function () {
		window.dispatchEvent(
			new CustomEvent('admin-toast', {
				detail: {
					message: '요청이 실패했습니다. 잠시 후 다시 시도해 주세요.',
					tone: 'failure',
				},
			})
		);
	});
})();

// x-show FOUC 방지(#1982 리뷰 반영): has-js 스코프 CSS로 Alpine 초기화 전 순간을 가려두고,
// Alpine이 실제로 모든 x-show를 최초 평가한 뒤(alpine:initialized) 이 클래스를 붙여 CSS 숨김을
// 해제한다. 그 시점부터는 x-show가 설정하는 인라인 style이 표시 여부를 전담한다.
// 이 훅이 없으면 Alpine의 x-show 구현(_x_doShow)이 인라인 display만 제거하고 값을 넣지 않아,
// CSS의 has-js 규칙이 계속 이겨 선택 후에도 bulk bar가 영원히 숨겨지는 회귀가 생긴다.
document.addEventListener('alpine:initialized', function () {
	document.body.classList.add('has-js-ready');
});

document.addEventListener('alpine:init', function () {
	// 반응형 사이드바 토글(#1738): ≤1024px에서 사이드바를 오프캔버스로 여닫는다.
	// 사이드바 표시는 body.sidebar-open 클래스가, 백드롭은 open 프로퍼티(x-show)가 함께 반영한다.
	// 메뉴 링크로 이동하면 페이지가 새로 로드되며 자연히 닫힌다. CSP 빌드: 메서드·게터 이름만 쓴다.
	Alpine.data('sidebarToggle', function () {
		return {
			open: false,
			get ariaExpanded() {
				return this.open ? 'true' : 'false';
			},
			setOpen: function (value) {
				this.open = value;
				document.body.classList.toggle('sidebar-open', value);
			},
			toggle: function () {
				this.setOpen(!this.open);
			},
			close: function () {
				this.setOpen(false);
			},
		};
	});

	// 사용자 메뉴 드롭다운(#2047): 상단바 우측 트리거를 누르면 계정 정보·로그아웃을 담은
	// 드롭다운이 열린다. sidebarToggle/alertCenter와 동일한 명명 컴포넌트 패턴 — CSP 빌드라
	// 메서드·게터 이름만 디렉티브에 넣는다. 진화형 향상: JS가 없으면 트리거는 계정 페이지 링크로
	// 동작하고 로그아웃 폼은 드롭다운 안에 그대로 렌더되어 접근 가능하다.
	// 업무 영역(workspace) disclosure(#2277): 사이드바를 7개 업무 영역 아코디언으로 접는다.
	// 서버는 모든 영역을 펼친 채(no-JS 폴백) 렌더하고, JS가 있으면 data-current="true"(현재 위치를
	// 담은 영역)만 펼치고 나머지는 접는다. 각 영역은 독립 x-data라 하나를 열어도 다른 영역은 그대로다.
	// 진화형 향상 — JS가 없으면 모든 영역이 펼쳐진 채 남아 허용 program 전부에 접근할 수 있다.
	// CSP 빌드 규약: x-on/x-bind에는 메서드·게터 이름만 쓴다.
	Alpine.data('navWorkspace', function () {
		return {
			expanded: true,
			init: function () {
				// 현재 위치가 없는 페이지(sidebar('')로 렌더되는 검색·알림·오류 등)는 서버가
				// .admin-nav-scroll에 is-no-current를 붙인다. 이때 어떤 영역도 data-current="true"가
				// 아니어서 전 영역이 접히는 회귀를 막기 위해 전 영역 펼침으로 폴백한다(#2277 리뷰).
				var scroll = this.$el.closest('.admin-nav-scroll');
				var noCurrent = scroll ? scroll.classList.contains('is-no-current') : false;
				this.expanded = noCurrent || this.$el.dataset.current === 'true';
			},
			get ariaExpanded() {
				return this.expanded ? 'true' : 'false';
			},
			toggle: function () {
				this.expanded = !this.expanded;
			},
		};
	});

	Alpine.data('userMenu', function () {
		return {
			open: false,
			get ariaExpanded() {
				return this.open ? 'true' : 'false';
			},
			toggle: function () {
				this.open = !this.open;
			},
			// close()는 상태만 닫고 포커스는 건드리지 않는다. 외부 클릭(x-on:click.outside)은 이
			// close()만 호출하므로, 사용자가 메뉴 밖의 다른 입력란을 눌러 닫힐 때 포커스를 트리거로
			// 빼앗지 않는다(#2049 리뷰: 포커스 도둑질 방지).
			close: function () {
				this.open = false;
			},
			// Esc(x-on:keydown.escape.window)로 닫을 때만 트리거로 포커스를 복원한다. 열려 있을 때만
			// 동작해 이미 닫힌 상태의 전역 Esc가 포커스를 트리거로 끌어오지 않게 하고, 패널 내부(로그아웃
			// 버튼)에 포커스가 남은 채 닫혀 display:none 요소에 포커스가 갇히는 문제를 키보드 경로에서만 막는다.
			closeFromKeyboard: function () {
				if (!this.open) {
					return;
				}
				this.close();
				this.$refs.trigger?.focus();
			},
		};
	});

	// 관리자 플래시/토스트 알림: JS가 있으면 닫기 버튼으로 사라진다. 없으면 그대로 표시된다.
	Alpine.data('dismissibleAlert', function () {
		return {
			visible: true,
			dismiss: function () {
				this.visible = false;
			},
		};
	});

	// 토스트: htmx 응답의 HX-Trigger("admin-toast", {message, tone}) 이벤트로 우상단에 알림을 띄운다.
	// 진화형 향상 — JS가 없으면 서버 flash(<output>)가 기존 자리에 그대로 렌더된다(no-JS 대체).
	// CSP 빌드 규약: x-on/x-text/x-show/x-bind에는 메서드·프로퍼티(게터) 이름만 쓴다.
	Alpine.data('toastHub', function () {
		return {
			message: '',
			tone: 'good',
			visible: false,
			get toneClass() {
				return 'is-' + this.tone;
			},
			show: function (event) {
				var detail = event && event.detail ? event.detail : {};
				this.message = detail.message || '';
				this.tone = detail.tone || 'good';
				this.visible = this.message !== '';
			},
			hide: function () {
				this.visible = false;
			},
		};
	});

	// sha/hash 복사(#1745): 원문은 title/details로 항상 확인 가능하고, JS가 있으면 버튼으로 클립보드에 복사한다.
	// Clipboard API가 막힌 브라우저에서는 조용히 버튼 문구만 유지한다(원문 확인 fallback은 이미 렌더됨).
	Alpine.data('copyField', function () {
		return {
			copy: function () {
				var value = this.$el.dataset.copyValue || '';
				if (!value || value === '-' || !navigator.clipboard) {
					return;
				}
				navigator.clipboard.writeText(value);
				this.$el.textContent = '복사됨';
			},
		};
	});

	// 커맨드 팔레트(#1738): Cmd/Ctrl+K로 열고, 검색 입력을 htmx로 /admin/search에 debounce 조회한다.
	// Esc·백드롭으로 닫고, 열릴 때 입력에 포커스. 방향키로 결과 링크 사이를 이동한다.
	// 진화형 향상 — JS가 없으면 topbar 검색 버튼이 /admin/search 전용 페이지로 이동한다(no-JS 대체).
	Alpine.data('commandPalette', function () {
		return {
			open: false,
			returnFocus: null,
			get ariaExpanded() {
				return this.open ? 'true' : 'false';
			},
			show: function () {
				this.returnFocus = document.activeElement;
				this.open = true;
				var self = this;
				this.$nextTick(function () {
					var input = self.$refs.input;
					if (input) {
						input.focus();
					}
				});
			},
			hide: function () {
				if (!this.open) {
					return;
				}
				this.open = false;
				adminReturnFocus(this, this.$refs.trigger);
			},
			trapFocusKey: function (event) {
				if (!this.open) {
					return;
				}
				var panel = this.$root.querySelector('.command-palette-panel');
				adminTrapTabKey(event, panel || this.$root);
			},
			// 입력에서 아래 방향키 → 첫 결과 링크로 포커스 이동.
			focusResults: function () {
				var first = this.$root.querySelector('#palette-results a');
				if (first) {
					first.focus();
				}
			},
			// 결과 링크에서 위/아래 방향키 → 인접 링크로 포커스 이동(CSP 빌드용 인자 없는 래퍼).
			moveDown: function (event) {
				this.moveFocus(event, 1);
			},
			moveUp: function (event) {
				this.moveFocus(event, -1);
			},
			moveFocus: function (event, delta) {
				var links = Array.prototype.slice.call(this.$root.querySelectorAll('#palette-results a'));
				var index = links.indexOf(event.target);
				var next = links[index + delta];
				if (next) {
					next.focus();
				} else if (delta < 0) {
					var input = this.$refs.input;
					if (input) {
						input.focus();
					}
				}
			},
		};
	});

	// 드로어(사이드 패널): htmx가 상세 fragment를 로드하고 HX-Trigger로 admin-drawer-open을 쏘면 열린다.
	// Esc·백드롭 클릭·닫기 버튼으로 닫고, 열릴 때 패널로 포커스를 옮긴다(접근성).
	// 진화형 향상 — JS가 없으면 상세 링크가 상세 페이지로 이동한다(no-JS 대체).
	Alpine.data('drawer', function () {
		return {
			visible: false,
			returnFocus: null,
			open: function () {
				this.returnFocus = document.activeElement;
				this.visible = true;
				var self = this;
				this.$nextTick(function () {
					adminFocusFirst(self.$refs.panel || self.$el);
				});
			},
			close: function () {
				if (!this.visible) {
					return;
				}
				this.visible = false;
				adminReturnFocus(this, null);
			},
			trapFocusKey: function (event) {
				if (!this.visible) {
					return;
				}
				adminTrapTabKey(event, this.$refs.panel || this.$el);
			},
		};
	});

	// 알림 센터(#1738): topbar 벨. 60초마다 /admin/alerts를 htmx로 폴링해 #admin-alert-live를 갱신한다.
	// 탭이 비활성(document.hidden)이면 폴링을 멈추고, 다시 활성화되면 즉시 갱신 후 재개한다(query budget 보호).
	// 벨 클릭으로 요약 패널을 여닫는다(열림 상태는 .admin-alert-center.is-open 클래스로 CSS가 표시).
	// 진화형 향상 — JS가 없으면 벨이 /admin/alerts 전용 페이지로 이동한다(no-JS 대체).
	// CSP 빌드 규약: x-on/x-bind에는 메서드·프로퍼티(게터) 이름만 쓴다.
	Alpine.data('alertCenter', function () {
		return {
			open: false,
			returnFocus: null,
			timer: null,
			get rootClass() {
				return this.open ? 'is-open' : '';
			},
			get ariaExpanded() {
				return this.open ? 'true' : 'false';
			},
			init: function () {
				var self = this;
				this.refresh();
				this.start();
				document.addEventListener('visibilitychange', function () {
					if (document.hidden) {
						self.stop();
					} else {
						self.refresh();
						self.start();
					}
				});
			},
			start: function () {
				if (this.timer) {
					return;
				}
				var self = this;
				this.timer = setInterval(function () {
					if (!document.hidden) {
						self.refresh();
					}
				}, 60000);
			},
			stop: function () {
				if (this.timer) {
					clearInterval(this.timer);
					this.timer = null;
				}
			},
			refresh: function () {
				if (window.htmx) {
					window.htmx.ajax('GET', '/admin/alerts', {
						target: '#admin-alert-live',
						swap: 'innerHTML',
					});
				}
			},
			toggle: function () {
				if (this.open) {
					this.closePanel();
				} else {
					this.openPanel();
				}
			},
			openPanel: function () {
				this.returnFocus = document.activeElement;
				this.open = true;
				var self = this;
				this.$nextTick(function () {
					var panel = self.$root.querySelector('.admin-alert-panel');
					adminFocusFirst(panel || self.$refs.live);
				});
			},
			closePanel: function () {
				if (!this.open) {
					return;
				}
				this.open = false;
				adminReturnFocus(this, this.$refs.trigger);
			},
			hide: function () {
				this.closePanel();
			},
			trapFocusKey: function (event) {
				if (!this.open) {
					return;
				}
				var panel = this.$root.querySelector('.admin-alert-panel');
				adminTrapTabKey(event, panel || this.$root);
			},
		};
	});

	// 운영 화면 자동 갱신(#1742): 실행 중 배치/수집·장애 목록을 새로고침 없이 반영한다.
	// alertCenter 폴링 패턴을 재사용한다 — 설정은 요소의 data-refresh-* 속성에서 읽고(CSP: 표현식 금지),
	// data-refresh-active="true"일 때만 폴링한다(배치/수집은 실행 중일 때만 요소가 렌더되어 없으면 정지).
	// 탭 비활성(document.hidden)이면 멈추고 활성화 시 즉시 갱신 후 재개한다(query budget 보호).
	// htmx 부분 갱신(hx-select로 live 영역만)이라 폼 포커스·스크롤은 스왑 영역 밖에서 보존된다.
	Alpine.data('autoRefresh', function () {
		return {
			timer: null,
			active: false,
			url: '',
			target: '',
			interval: 60000,
			onVisibility: null,
			init: function () {
				var dataset = this.$el.dataset;
				this.url = dataset.refreshUrl || '';
				this.target = dataset.refreshTarget || '';
				this.interval = parseInt(dataset.refreshInterval, 10) || 60000;
				this.active = dataset.refreshActive === 'true';
				if (!this.active || !this.url || !this.target) {
					return;
				}
				var self = this;
				this.onVisibility = function () {
					if (document.hidden) {
						self.stop();
					} else {
						self.refresh();
						self.start();
					}
				};
				document.addEventListener('visibilitychange', this.onVisibility);
				this.start();
			},
			start: function () {
				if (this.timer || !this.active) {
					return;
				}
				var self = this;
				this.timer = setInterval(function () {
					if (!document.hidden) {
						self.refresh();
					}
				}, this.interval);
			},
			stop: function () {
				if (this.timer) {
					clearInterval(this.timer);
					this.timer = null;
				}
			},
			refresh: function () {
				if (window.htmx) {
					window.htmx.ajax('GET', this.url, {
						target: this.target,
						select: this.target,
						swap: 'outerHTML',
					});
				}
			},
			destroy: function () {
				this.stop();
				if (this.onVisibility) {
					document.removeEventListener('visibilitychange', this.onVisibility);
					this.onVisibility = null;
				}
			},
		};
	});

	// 표준 테이블: 일괄 선택(선택 수·전체 선택) + 밀도 3단 + 컬럼 표시 토글.
	// 진화형 향상 — JS가 없으면 개별 체크박스 + 액션 버튼(no-JS 폼)이 그대로 동작하고, 표는 기본 밀도로 보인다.
	// CSP 빌드 규약: x-on/x-text/x-bind에는 메서드·프로퍼티(게터) 이름만 쓰고 표현식은 쓰지 않는다.
	Alpine.data('reportTable', function () {
		return {
			count: 0,
			density: 'default',
			hideCoordinate: false,
			hidePhoto: false,
			helpVisible: false,
			get selectionLabel() {
				return this.count > 0 ? '선택한 신고 ' + this.count + '건' : '선택한 신고 일괄 처리';
			},
			get hasSelection() {
				return this.count > 0;
			},
			get tableClass() {
				return 'density-' + this.density
					+ (this.hideCoordinate ? ' hide-col-coordinate' : '')
					+ (this.hidePhoto ? ' hide-col-photo' : '');
			},
			recount: function () {
				this.count = this.$root.querySelectorAll('input[name="reportIds"]:checked').length;
			},
			toggleAll: function (event) {
				var checked = event.target.checked;
				this.$root.querySelectorAll('input[name="reportIds"]').forEach(function (checkbox) {
					checkbox.checked = checked;
				});
				this.recount();
			},
			setDensity: function (event) {
				this.density = event.target.value;
			},
			toggleCoordinate: function (event) {
				this.hideCoordinate = event.target.checked;
			},
			togglePhoto: function (event) {
				this.hidePhoto = event.target.checked;
			},
			// 키보드 단축키(#1740): j/k 행 이동·o 상세 열기·a 승인·r 반려·Esc 닫기·? 도움말.
			// 모더레이터가 마우스 없이 대기열을 처리하도록 한다(Reddit modqueue·모더레이션 API 공통 패턴).
			// 진화형 향상 — 모든 단축키 동작은 버튼(상세 링크·일괄 승인/반려·도움말)으로도 존재해 스크린리더/no-JS를
			// 커버한다. 입력·select·textarea 포커스 중에는 비활성해 타이핑을 방해하지 않는다.
			// 런타임/실제 키 입력 검증은 브라우저가 필요해 접근성 QA(#1749)로 이월한다.
			get helpExpanded() {
				return this.helpVisible ? 'true' : 'false';
			},
			toggleHelp: function () {
				this.helpVisible = !this.helpVisible;
			},
			hideHelp: function () {
				this.helpVisible = false;
			},
			rowLinks: function () {
				return Array.prototype.slice.call(this.$root.querySelectorAll('.report-row .detail-link'));
			},
			// 현재 포커스가 향한 행의 상세 링크. 포커스가 표 밖이면 첫 행으로 대체한다(비파괴 동작 전용).
			currentLink: function () {
				var links = this.rowLinks();
				if (!links.length) {
					return null;
				}
				return this.focusedRowLink() || links[0];
			},
			// 포커스가 실제로 어느 행 안에 있을 때만 그 행의 상세 링크를 준다(없으면 null, 첫 행 폴백 없음).
			// 승인·반려처럼 파괴적인 단축키가 포커스가 표 밖일 때 첫 행을 잘못 처리하지 않게 한다.
			focusedRowLink: function () {
				var active = document.activeElement;
				var links = this.rowLinks();
				if (links.indexOf(active) !== -1) {
					return active;
				}
				var row = active && active.closest ? active.closest('.report-row') : null;
				return row ? row.querySelector('.detail-link') : null;
			},
			isFormField: function (element) {
				if (!element) {
					return false;
				}
				var tag = element.tagName ? element.tagName.toLowerCase() : '';
				return tag === 'input' || tag === 'textarea' || tag === 'select' || element.isContentEditable;
			},
			moveActive: function (delta) {
				var links = this.rowLinks();
				if (!links.length) {
					return;
				}
				var index = links.indexOf(document.activeElement);
				var next;
				if (index === -1) {
					next = delta > 0 ? links[0] : links[links.length - 1];
				} else {
					next = links[index + delta];
				}
				if (next) {
					next.focus();
				}
			},
			openActive: function () {
				var link = this.currentLink();
				if (link) {
					link.click();
				}
			},
			// 활성 행 하나만 선택 상태로 만들고 일괄 검수 폼을 해당 결정으로 제출한다(단일 처리 시맨틱).
			// 포커스가 표 밖이면 아무 것도 하지 않는다(첫 행 오처리 방지) — focusedRowLink는 폴백이 없다.
			// 반려(REJECT)는 details 확인 UI를 연 뒤 확인 버튼에 포커스만 옮긴다 — 단축키로 확인 단계를
			// 건너뛰지 않는다(#2416 Bugbot). 승인은 기존처럼 즉시 제출한다.
			processActive: function (decision) {
				var link = this.focusedRowLink();
				if (!link) {
					return;
				}
				var row = link.closest('.report-row');
				var form = this.$root.querySelector('.bulk-form');
				if (!row || !form) {
					return;
				}
				this.$root.querySelectorAll('input[name="reportIds"]').forEach(function (checkbox) {
					checkbox.checked = false;
				});
				var checkbox = row.querySelector('input[name="reportIds"]');
				if (checkbox) {
					checkbox.checked = true;
				}
				this.recount();
				if (decision === 'REJECT') {
					var confirm = form.querySelector('details.admin-action-confirm');
					var rejectBtn = confirm ? confirm.querySelector('button[value="REJECT"]') : null;
					if (confirm && rejectBtn) {
						confirm.open = true;
						this.$nextTick(function () {
							rejectBtn.focus();
						});
						return;
					}
				}
				var button = form.querySelector('button[value="' + decision + '"]');
				if (button) {
					button.click();
				}
			},
			handleKey: function (event) {
				if (this.isFormField(document.activeElement) || event.ctrlKey || event.metaKey || event.altKey) {
					return;
				}
				switch (event.key) {
					case '?':
						this.toggleHelp();
						event.preventDefault();
						break;
					case 'j':
						this.moveActive(1);
						event.preventDefault();
						break;
					case 'k':
						this.moveActive(-1);
						event.preventDefault();
						break;
					case 'o':
						this.openActive();
						event.preventDefault();
						break;
					case 'a':
						this.processActive('ACCEPT');
						event.preventDefault();
						break;
					case 'r':
						this.processActive('REJECT');
						event.preventDefault();
						break;
					case 'Escape':
						if (this.helpVisible) {
							this.hideHelp();
						}
						break;
					default:
						break;
				}
			},
		};
	});

	// 실패 푸시 재발송 선택(#1746): 확인 단계에 선택 건수를 보여주고, 선택이 없으면 제출을 막는다.
	// 진화형 향상 — no-JS에서는 버튼이 항상 활성이고 서버가 빈 선택을 안내한다. 런타임/키 검증은 #1749.
	Alpine.data('pushResendSelection', function () {
		return {
			count: 0,
			get selectionLabel() {
				return this.count > 0 ? '선택한 실패 ' + this.count + '건 재발송' : '재발송할 실패 건 선택';
			},
			get hasSelection() {
				return this.count > 0;
			},
			// CSP 빌드는 x-bind 안의 연산자(!)를 평가하지 않는다 — 부정은 getter로 노출한다.
			get submitDisabled() {
				return this.count === 0;
			},
			recount: function () {
				this.count = this.$root.querySelectorAll('input[name="notificationIds"]:checked').length;
			},
		};
	});

	// 목록 툴바 시트(#2278 V6-06): compact에서 필터·보기 설정을 시트로 여닫는다. 진화형 향상 —
	// JS가 없으면 시트 트리거(x-cloak)는 숨고 시트 내용은 인라인으로 남아 form/link가 그대로 동작한다.
	// userMenu와 동일한 포커스 정책: close()는 상태만 닫아 외부 클릭(x-on:click.outside) 시 포커스를
	// 트리거로 빼앗지 않고(입력 미유실), Esc(closeFromKeyboard)로 닫을 때만 트리거로 포커스를 복원한다.
	// 리스너는 Alpine 디렉티브(x-on)로만 선언해 컴포넌트가 교체될 때 자동 정리된다 — 수동 addEventListener나
	// 폴링이 없어 htmx 부분 갱신으로 툴바가 다시 렌더돼도 리스너가 중복 등록되지 않는다.
	// CSP 빌드 규약: x-on/x-bind에는 메서드·게터 이름만 쓴다.
	Alpine.data('listToolbar', function () {
		return {
			filterOpen: false,
			viewOpen: false,
			get filterExpanded() {
				return this.filterOpen ? 'true' : 'false';
			},
			get viewExpanded() {
				return this.viewOpen ? 'true' : 'false';
			},
			get filterSheetClass() {
				return this.filterOpen ? 'is-open' : '';
			},
			get viewSheetClass() {
				return this.viewOpen ? 'is-open' : '';
			},
			toggleFilter: function () {
				this.filterOpen = !this.filterOpen;
			},
			toggleView: function () {
				this.viewOpen = !this.viewOpen;
			},
			closeFilter: function () {
				this.filterOpen = false;
			},
			closeView: function () {
				this.viewOpen = false;
			},
			closeFilterFromKeyboard: function () {
				if (!this.filterOpen) {
					return;
				}
				this.filterOpen = false;
				this.$refs.filterTrigger?.focus();
			},
			closeViewFromKeyboard: function () {
				if (!this.viewOpen) {
					return;
				}
				this.viewOpen = false;
				this.$refs.viewTrigger?.focus();
			},
		};
	});
});
