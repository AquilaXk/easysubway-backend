## 관련 이슈

close #

## 작업 배경

-

## 작업 내용

-

## 검증

- 실행한 명령과 결과:

## 검증 증거

접근성, 수동 QA, 배포 확인이 필요한 항목은 증거 첨부, 링크, 또는 로컬 evidence 경로를 적습니다. 증거가 필요 없는 항목은 사유를 적습니다.

| 항목 | 컴포넌트 | 확인 방법 | 증거 | 결과 |
| --- | --- | --- | --- | --- |
|  |  |  |  |  |

## Version impact

- [ ] contracts/release 변경 (A등급)
- [ ] no version change
- [ ] backend deploy only
- [ ] backend API contract change
- [ ] route/realtime contract change
- [ ] DB migration change

## Route commercialization gate impact

- [ ] route 상용화 gate 지표 영향 없음
- [ ] tools/routes의 route ETA accuracy, realtime coverage, route v2 contract report를 갱신했다 (gate JSON 집계는 hub 레포 소유).
- [ ] 상용 경로/ETA claim을 추가하거나 변경하지 않는다.

## Route release readiness tracker impact

- [ ] release readiness tracker 영향 없음
- [ ] release blocker issue 또는 production evidence 완료 조건을 갱신했다 (hub tracker 반영이 필요하면 hub 이슈를 함께 참조).
- [ ] 실시간/교통약자 길찾기 출시 준비 완료 claim을 추가하거나 변경하지 않는다.

### Version decision

- contracts/release version (변경 전 → 변경 후):
- backend image digest:
- backend API contract version:
- route contract:
- realtime contract:
- backend identity:

## 리뷰어 메모

- 리뷰어가 먼저 봐야 할 지점:

## 리스크

-

## 체크리스트

- [ ] PR 본문은 이 템플릿 섹션을 삭제하지 않고 모두 채웠다.
- [ ] CI 결과를 확인했다.
- [ ] CodeRabbit 리뷰를 확인했다.
- [ ] GitHub PR Review 객체가 있는지 확인했다. CodeRabbit status check만으로는 리뷰 완료로 보지 않는다.
- [ ] CodeRabbit 실행이 불가능하거나 PR Review 객체가 없으면 폴백 code review를 단일 PR review로 게시했다.
- [ ] 배포 영향이 있는 경우 CD 상태를 확인했다.
