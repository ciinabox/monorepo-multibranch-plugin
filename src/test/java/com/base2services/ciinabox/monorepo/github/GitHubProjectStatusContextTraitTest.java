package com.base2services.ciinabox.monorepo.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.base2services.ciinabox.monorepo.ProjectSourceCustomizer;
import hudson.ExtensionList;
import java.util.List;
import jenkins.scm.api.SCMHeadObserver;
import org.jenkinsci.plugins.github_branch_source.AbstractGitHubNotificationStrategy;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSourceContext;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

class GitHubProjectStatusContextTraitTest {
    @Test
    void theProjectGoesBeforeTheKindOfBuild() {
        assertEquals("continuous-integration/jenkins/services/api/pr-head",
                GitHubProjectStatusContextTrait.contextFor("continuous-integration/jenkins/pr-head", "services/api"));
        assertEquals("continuous-integration/jenkins/(root)/branch",
                GitHubProjectStatusContextTrait.contextFor("continuous-integration/jenkins/branch", "(root)"));
        assertEquals("libs/common/custom", GitHubProjectStatusContextTrait.contextFor("custom", "libs/common"));
    }

    @Test
    @WithJenkins
    void eachProjectsSourceReportsUnderItsOwnContext(JenkinsRule j) {
        List<AbstractGitHubNotificationStrategy> api = strategies("services/api");
        assertEquals(List.of(new GitHubProjectStatusContextTrait.Strategy("services/api")), api);
        assertNotEquals(api, strategies("services/web"));
    }

    @Test
    @WithJenkins
    void customizingTwiceKeepsOneTrait(JenkinsRule j) {
        GitHubSCMSource source = new GitHubSCMSource("org", "repo", null, false);
        for (ProjectSourceCustomizer c : ExtensionList.lookup(ProjectSourceCustomizer.class)) {
            c.customize(source, "a");
            c.customize(source, "b");
        }
        assertEquals(List.of("b"), source.getTraits().stream()
                .filter(GitHubProjectStatusContextTrait.class::isInstance)
                .map(t -> ((GitHubProjectStatusContextTrait) t).getProject()).toList());
    }

    private static List<AbstractGitHubNotificationStrategy> strategies(String project) {
        GitHubSCMSource source = new GitHubSCMSource("org", "repo", null, false);
        for (ProjectSourceCustomizer c : ExtensionList.lookup(ProjectSourceCustomizer.class)) {
            c.customize(source, project);
        }
        // how github-branch-source works out where to report a build
        return new GitHubSCMSourceContext(null, SCMHeadObserver.none())
                .withTraits(source.getTraits()).notificationStrategies();
    }
}
