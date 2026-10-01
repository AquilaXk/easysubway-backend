package com.easysubway.transit.adapter.out.seoul;

/**
 * 서울교통공사 원천 응답이 형식 계약을 어긴 경우. 메시지는 사유 코드만 담고 원천 응답·인증키를 담지 않는다.
 */
final class SeoulMetroElevatorFeedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	SeoulMetroElevatorFeedException(String reason) {
		super(reason);
	}
}
