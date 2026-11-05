package com.rikkahub.agent.skill;

import java.util.List;
import java.util.Optional;

/** Host-owned skill catalog. Implementations must not expose arbitrary filesystem paths. */
public interface SkillSource {
    List<SkillSummary> summaries();

    Optional<Skill> load(SkillId id);
}
