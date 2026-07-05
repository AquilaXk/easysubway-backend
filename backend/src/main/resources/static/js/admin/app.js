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
});
