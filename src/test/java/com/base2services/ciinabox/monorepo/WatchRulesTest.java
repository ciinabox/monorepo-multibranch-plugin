package com.base2services.ciinabox.monorepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WatchRulesTest {
    private static WatchRules.FileReader files(Map<String, String> files) {
        return files::get;
    }

    @Test
    void aProjectWatchesItsOwnDirectory() throws Exception {
        assertEquals(Set.of("services/api/**"), WatchRules.patterns("services/api", files(Map.of())));
        assertEquals(Set.of("**"), WatchRules.patterns("", files(Map.of())));
    }

    @Test
    void watchAddsGlobs() throws Exception {
        Set<String> patterns = WatchRules.patterns("services/api",
                files(Map.of("services/api/monorepo.yaml", "watch: ['shared/schemas/**', '/tools/*.sh']")));
        assertEquals(Set.of("services/api/**", "shared/schemas/**", "tools/*.sh"), patterns);
    }

    @Test
    void dependsOnIsTransitiveAndSurvivesCycles() throws Exception {
        Set<String> patterns = WatchRules.patterns("services/api", files(Map.of(
                "services/api/monorepo.yaml", "dependsOn: [libs/common/]",
                "libs/common/monorepo.yaml", "dependsOn: [libs/base, services/api]\nwatch: ['proto/**']",
                "libs/base/monorepo.yaml", "watch: ['versions.toml']")));
        assertEquals(Set.of("services/api/**", "libs/common/**", "proto/**", "libs/base/**", "versions.toml"),
                patterns);
    }

    @Test
    void aMalformedFileIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> WatchRules.patterns("a",
                files(Map.of("a/monorepo.yaml", "watch: not-a-list"))));
    }

    @Test
    void matching() {
        assertNotNull(WatchRules.firstMatch(Set.of("services/api/**"), List.of("services/api/src/Main.java")));
        assertNull(WatchRules.firstMatch(Set.of("services/api/**"), List.of("services/api-v2/x", "services/apix")));
        assertNotNull(WatchRules.firstMatch(Set.of("**/*.proto"), List.of("a.proto")));
        assertNotNull(WatchRules.firstMatch(Set.of("**/*.proto"), List.of("x/y/a.proto")));
        assertNull(WatchRules.firstMatch(Set.of("tools/*.sh"), List.of("tools/sub/a.sh")));
        assertNotNull(WatchRules.firstMatch(Set.of("**"), List.of("anything/at/all")));
        assertNull(WatchRules.firstMatch(Set.of("a.b"), List.of("aXb")));
    }
}
