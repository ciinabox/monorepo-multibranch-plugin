package com.base2services.ciinabox.monorepo.bitbucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.base2services.ciinabox.monorepo.AggregateStatusPublisher.State;
import com.cloudbees.jenkins.plugins.bitbucket.BranchSCMHead;
import com.cloudbees.jenkins.plugins.bitbucket.PullRequestSCMHead;
import com.cloudbees.jenkins.plugins.bitbucket.PullRequestSCMRevision;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketBuildStatus;
import com.cloudbees.jenkins.plugins.bitbucket.api.PullRequestBranchType;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.junit.jupiter.api.Test;

class BitbucketAggregateStatusPublisherTest {
    private static final String TARGET = "1111111111111111111111111111111111111111";

    private static PullRequestSCMHead head() {
        return new PullRequestSCMHead("PR-7", "team", "mono", "feature/web", PullRequestBranchType.BRANCH, "7",
                "web", new BranchSCMHead("main"), SCMHeadOrigin.DEFAULT, ChangeRequestCheckoutStrategy.MERGE);
    }

    @Test
    void theCommitIsThePullRequestsOwn() {
        PullRequestSCMHead head = head();
        PullRequestSCMRevision revision = new PullRequestSCMRevision(head,
                new AbstractGitSCMSource.SCMRevisionImpl(head.getTarget(), TARGET),
                new AbstractGitSCMSource.SCMRevisionImpl(head, "abcdef123456"));
        assertEquals("abcdef123456", new BitbucketAggregateStatusPublisher().commit(revision));
        assertNull(new BitbucketAggregateStatusPublisher().commit(
                new jenkins.scm.api.SCMRevision(head) {
                    @Override public boolean equals(Object o) { return o == this; }
                    @Override public int hashCode() { return 0; }
                }));
    }

    @Test
    void cloudTakesTheBranchNameAndDataCenterTheRef() {
        BitbucketBuildStatus cloud = BitbucketAggregateStatusPublisher.status(
                head(), "abcdef123456", State.PENDING, "0 of 2 affected projects finished", "https://jenkins/job/mono/", true);
        assertEquals("feature/web", cloud.getRefname());
        assertEquals(BitbucketAggregateStatusPublisher.KEY, cloud.getKey());
        assertEquals("abcdef123456", cloud.getHash());
        assertEquals(BitbucketBuildStatus.Status.INPROGRESS, cloud.getState());
        assertEquals("https://jenkins/job/mono/", cloud.getUrl());

        BitbucketBuildStatus server = BitbucketAggregateStatusPublisher.status(
                head(), "abcdef123456", State.FAILURE, "Failed: services/web (1 affected project)", "u", false);
        assertEquals("refs/heads/feature/web", server.getRefname());
        assertEquals(BitbucketBuildStatus.Status.FAILED, server.getState());
        assertEquals(BitbucketBuildStatus.Status.SUCCESSFUL, BitbucketAggregateStatusPublisher.status(
                head(), "a", State.SUCCESS, "d", "u", true).getState());
    }
}
