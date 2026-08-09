package com.easysubway.journey.bundle;

/** A request-safe, opaque runtime view prepared by the fixture test. */
public record RouteBundleRuntimeView(String value) {

    public RouteBundleRuntimeView {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("runtime view must be present");
        }
    }
}
