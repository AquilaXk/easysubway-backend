package com.easysubway.route.application.service;

public class ItxTimetableUnavailableException extends RuntimeException {

	public ItxTimetableUnavailableException() {
		super("ITX timetable is unavailable");
	}
}
