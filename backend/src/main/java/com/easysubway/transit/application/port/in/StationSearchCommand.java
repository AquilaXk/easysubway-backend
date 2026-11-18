package com.easysubway.transit.application.port.in;

public record StationSearchCommand(String query, String lineId) {
}
