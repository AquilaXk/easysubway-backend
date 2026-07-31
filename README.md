# EasySubway Backend

EasySubway의 경로 검색, 실시간 도착, 관리자 및 데이터팩 control-plane을 제공하는 Spring Boot backend입니다.

- Java 21 · Spring Boot 3.5
- 하나의 modular monolith와 하나의 immutable container image
- API 계약은 `AquilaXk/easysubway`의 고정된 contract bundle을 소비

현재 production image publication은 platform cutover 검증이 끝날 때까지 기존 hub에서 유지됩니다.
