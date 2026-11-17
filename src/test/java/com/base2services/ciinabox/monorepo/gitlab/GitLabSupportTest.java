package com.base2services.ciinabox.monorepo.gitlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.base2services.ciinabox.monorepo.AggregateStatusPublisher.State;
import io.jenkins.plugins.gitlabbranchsource.BranchSCMHead;
import io.jenkins.plugins.gitlabbranchsource.BranchSCMRevision;
import io.jenkins.plugins.gitlabbranchsource.BuildStatusNameCustomPartTrait;
import io.jenkins.plugins.gitlabbranchsource.GitLabSCMSource;
import io.jenkins.plugins.gitlabbranchsource.MergeRequestSCMHead;
import io.jenkins.plugins.gitlabbranchsource.MergeRequestSCMRevision;
import java.util.List;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.gitlab4j.api.Constants;
import org.gitlab4j.api.models.CommitStatus;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

class GitLabSupportTest {
    private static MergeRequestSCMRevision mergeRequest() {
        BranchSCMHead target = new BranchSCMHead("main");
        MergeRequestSCMHead head = new MergeRequestSCMHead("MR-3", 3, target, ChangeRequestCheckoutStrategy.MERGE,
                SCMHeadOrigin.DEFAULT, "group", "group/mono", "feature/web", "web");
        return new MergeRequestSCMRevision(head, new BranchSCMRevision(target, "1111111"),
                new BranchSCMRevision(new BranchSCMHead("feature/web"), "2222222"));
    }

    @Test
    void theCommitIsTheMergeRequestsHead() {
        assertEquals("2222222", new GitLabSupport.Publisher().commit(mergeRequest()));
        assertEquals("1111111", new GitLabSupport.Publisher().commit(
                new BranchSCMRevision(new BranchSCMHead("main"), "1111111")));
    }

    @Test
    void theStatusGoesOnTheSourceBranch() {
        CommitStatus status = GitLabSupport.Publisher.status(mergeRequest(), "3 affected projects passed", "https://jenkins/");
        assertEquals(GitLabSupport.NAME, status.getName());
        assertEquals("feature/web", status.getRef());
        assertEquals("3 affected projects passed", status.getDescription());
        assertEquals("https://jenkins/", status.getTargetUrl());
        assertNull(GitLabSupport.Publisher.status(new BranchSCMRevision(new BranchSCMHead("main"), "1"), "d", "u").getRef());
    }

    @Test
    void states() {
        assertEquals(Constants.CommitBuildState.SUCCESS, GitLabSupport.Publisher.state(State.SUCCESS));
        assertEquals(Constants.CommitBuildState.FAILED, GitLabSupport.Publisher.state(State.FAILURE));
        assertEquals(Constants.CommitBuildState.RUNNING, GitLabSupport.Publisher.state(State.PENDING));
    }

    @Test
    @WithJenkins
    void eachProjectGetsItsOwnStatusName(JenkinsRule j) {
        GitLabSCMSource source = new GitLabSCMSource("gitlab-com", "group", "group/mono");
        BuildStatusNameCustomPartTrait existing = new BuildStatusNameCustomPartTrait();
        existing.setBuildStatusNameCustomPart("something-else");
        source.setTraits(List.of(existing));
        new GitLabSupport.Customizer().customize(source, "services/api");
        new GitLabSupport.Customizer().customize(source, "services/web");
        List<String> parts = source.getTraits().stream()
                .filter(BuildStatusNameCustomPartTrait.class::isInstance)
                .map(t -> ((BuildStatusNameCustomPartTrait) t).getBuildStatusNameCustomPart())
                .toList();
        assertEquals(List.of("services/web"), parts);
    }
}
