package com.rikkahub.features.fixtureb.infrastructure;

/** Test fixture: repository that another feature must not access directly. */
public class FixtureBRepository {
    public String value() {
        return "fixture";
    }
}
