<!-- A등급: high-risk API, schema, database, security, runtime, artifact, release, CI·contract 변경. -->

## Related issue

Related #

## Summary

- Problem:
- Outcome:

## Changes

-

## Scope

### Included

-

### Excluded

-

### Ownership / dependencies

- Accountable owner or plan:
- Required predecessor output:
- Concurrent work overlap: None

## Contract & Compatibility

- API / schema / database contract:
- Runtime / readiness identity:
- Backward compatibility:
- Migration or cutover:

## Version impact

- [ ] no version change
- [ ] backend deploy only
- [ ] backend API contract change
- [ ] route/realtime contract change
- [ ] DB migration change

## Route commercialization gate impact

- [ ] route 상용화 gate 지표 영향 없음
- [ ] route ETA accuracy, realtime coverage, route v2 contract report를 갱신했다.
- [ ] 상용 경로/ETA claim을 추가하거나 변경하지 않는다.

## Route release readiness tracker impact

- [ ] release readiness tracker 영향 없음
- [ ] release blocker issue 또는 production evidence 완료 조건을 갱신했다.
- [ ] 실시간/교통약자 길찾기 출시 준비 완료 claim을 추가하거나 변경하지 않는다.

### Version decision

- contracts/release version:
- backend image digest:
- backend API / route / realtime contract:
- backend identity:

## Verification

| Check | Result / Evidence |
| --- | --- |
| Focused RED → GREEN | |
| Affected integration | |
| Required CI | |
| Runtime / production-like | Not required — reason: |
| Security / data integrity | Not applicable — reason: |

## Not run

- Check: None
- Reason:
- Rerun owner / condition:

## Risk

- Level: High
- Main risk:
- Failure behavior:
- Response / readiness / active-state mutation on failure:
- Fallback or degraded-success path introduced: No

## Rollout / Recovery

- Rollout or activation:
- Monitoring / success signal:
- Rollback or recovery:
- API / schema / data compatibility after rollback:

## Review focus

-

## Checklist

- [ ] 이슈 범위와 실제 diff가 일치합니다.
- [ ] 관련 없는 변경이나 다른 owner의 surface를 포함하지 않았습니다.
- [ ] 위험에 필요한 검증과 미실행 사유를 기록했습니다.
- [ ] 실패·호환성·rollout·recovery 동작이 명확합니다.
- [ ] current failure를 이전·local·legacy 경로의 성공으로 바꾸지 않습니다.
- [ ] GitHub PR Review 객체가 있는지 확인했습니다. CodeRabbit status check만으로는 리뷰 완료로 보지 않습니다.
- [ ] CodeRabbit Review 객체가 없으면 지원되는 Codex CLI 폴백 Review를 단일 GitHub PR Review로 게시했습니다.
- [ ] 배포 영향이 있는 경우 CD 상태를 확인했습니다.
