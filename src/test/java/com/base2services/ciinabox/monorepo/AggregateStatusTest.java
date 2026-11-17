package com.base2services.ciinabox.monorepo;

import static com.base2services.ciinabox.monorepo.AggregateStatusPublisher.State.FAILURE;
import static com.base2services.ciinabox.monorepo.AggregateStatusPublisher.State.PENDING;
import static com.base2services.ciinabox.monorepo.AggregateStatusPublisher.State.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class AggregateStatusTest {
    @Test
    void aPullRequestThatAffectsNothingPasses() {
        assertEquals(new AggregateStatus.Summary(SUCCESS, "No projects affected"), AggregateStatus.summarise(Map.of()));
    }

    @Test
    void pendingUntilEveryAffectedProjectHasFinished() {
        assertEquals(new AggregateStatus.Summary(PENDING, "1 of 2 affected projects finished"),
                AggregateStatus.summarise(new TreeMap<>(Map.of("a", SUCCESS, "b", PENDING))));
        assertEquals(new AggregateStatus.Summary(SUCCESS, "2 affected projects passed"),
                AggregateStatus.summarise(new TreeMap<>(Map.of("a", SUCCESS, "b", SUCCESS))));
        assertEquals(new AggregateStatus.Summary(SUCCESS, "1 affected project passed"),
                AggregateStatus.summarise(Map.of("a", SUCCESS)));
    }

    @Test
    void aFailureShowsEvenWhileOthersRun() {
        assertEquals(new AggregateStatus.Summary(FAILURE, "Failed: b, c (4 affected projects)"),
                AggregateStatus.summarise(new TreeMap<>(Map.of("a", SUCCESS, "b", FAILURE, "c", FAILURE, "d", PENDING))));
    }
}
