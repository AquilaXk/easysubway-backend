# 쉬운 지하철 백엔드

### 이동 제약을 고려한 경로, 실시간 열차 정보, 시설 제보를 든든하게 뒷받침합니다.

이동 제약을 고려한 경로, 실시간 열차 정보, 시설 제보를 EasySubway 모바일 앱에 제공하고 운영자가 데이터 품질을 관리할 수 있게 하는 백엔드입니다.

<br>

[ 🇺🇸 English ](./README.md) | [ 한국어 ]

<br>

## 제공하는 기능
- **경로 탐색**: 출발역, 도착역, 출발 시각, 이동 제약과 환승 조건을 바탕으로 갈 수 있는 길을 찾습니다.
- **실시간 열차 정보**: 역 도착 정보와 노선별 열차 위치를 앱에 전달합니다.
- **열차 검색**: 전국 여객열차 역 목록과 편도, 왕복 열차편을 조회합니다.
- **시설 제보**: 사진을 첨부할 수 있는 제보를 접수하고, 접수 후 처리 상태를 영수증 토큰으로 확인합니다.
- **운영 데이터 관리**: 역, 시설, 역사 구조, 경로 데이터를 검수하고 데이터팩 후보와 배포 채널을 관리합니다.
- **운영 현황 관측**: 경로 피드백, 반복 고장 시설, 알림 발송, 데이터 수집 상태 등 서비스 품질 지표를 확인합니다.

## 현재 범위
- 모바일 앱 연동용 API와 인증된 운영 화면을 함께 제공합니다. 운영 기능은 일반 공개 API가 아닙니다.
- 실시간 정보의 현재 연동 대상은 서울 TOPIS입니다.
- 운영 환경의 정본 경로 탐색은 Journey V3 서버에서 RAPTOR 알고리즘으로 수행합니다. 서버는 검증된 active route bundle을 사용하며, 필요한 데이터나 실시간 정보가 없거나 만료되면 로컬이나 이전 결과로 어설프게 대체하지 않고 명시적인 오류를 반환합니다.
- 제보는 사진 없이도 접수됩니다. 익명 제보는 사전 서명된 업로드 URL을 사용합니다.
- 데이터 변경과 배포, 실시간 제공자 제어는 권한이 있는 운영자만 수행합니다.

## API 계약 및 문의
서버 주소와 운영자 자격증명은 공개하지 않습니다. 엔드포인트 명세, 헤더 및 스키마는 계약 파일에 정의되어 있습니다:
- [Realtime API Contract](contracts/api/realtime-api.openapi.yaml)
- [Train API Contract](contracts/api/train-api.openapi.yaml)
- [Report API Contract](contracts/api/report-api.openapi.yaml)
- [Journey V3 Route API Contract](contracts/api/journey-v3.openapi.yaml)

문의: [aquila@aquilaxk.site](mailto:aquila@aquilaxk.site)
