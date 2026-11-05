package com.rikkahub.features.fixturea;

import com.rikkahub.features.fixtureb.infrastructure.FixtureBRepository;

/** Test fixture: intentionally violates the cross-feature infrastructure rule. */
public class FixtureAController {
    private final FixtureBRepository repository = new FixtureBRepository();

    public String load() {
        return repository.value();
    }
}
