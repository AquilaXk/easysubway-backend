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
document.addEventListener('alpine:init', function () {
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

	// 드로어(사이드 패널): htmx가 상세 fragment를 로드하고 HX-Trigger로 admin-drawer-open을 쏘면 열린다.
	// Esc·백드롭 클릭·닫기 버튼으로 닫고, 열릴 때 패널로 포커스를 옮긴다(접근성).
	// 진화형 향상 — JS가 없으면 상세 링크가 상세 페이지로 이동한다(no-JS 대체).
	Alpine.data('drawer', function () {
		return {
			visible: false,
			open: function () {
				this.visible = true;
				var panel = this.$refs.panel;
				var focusPanel = function () {
					if (panel) {
						panel.focus();
					}
				};
				if (this.$nextTick) {
					this.$nextTick(focusPanel);
				} else {
					focusPanel();
				}
			},
			close: function () {
				this.visible = false;
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
		};
	});
});
