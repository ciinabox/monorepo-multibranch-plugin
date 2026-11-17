package com.base2services.ciinabox.monorepo.bitbucket;

import com.base2services.ciinabox.monorepo.AggregateStatusPublisher;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource;
import com.cloudbees.jenkins.plugins.bitbucket.PullRequestSCMHead;
import com.cloudbees.jenkins.plugins.bitbucket.PullRequestSCMRevision;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketApi;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketAuthenticatedClient;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketBuildStatus;
import com.cloudbees.jenkins.plugins.bitbucket.api.buildstatus.BitbucketBuildStatusNotifier;
import com.cloudbees.jenkins.plugins.bitbucket.api.endpoint.BitbucketEndpoint;
import com.cloudbees.jenkins.plugins.bitbucket.api.endpoint.BitbucketEndpointProvider;
import com.cloudbees.jenkins.plugins.bitbucket.api.endpoint.EndpointType;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.TaskListener;
import java.io.IOException;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;

/**
 * The monorepo's overall status on Bitbucket: a build status with the key {@value #KEY} on the pull
 * request's head commit. The projects' own statuses need nothing: Bitbucket keys them by job name,
 * which already has the project in it.
 */
@Extension(optional = true)
public class BitbucketAggregateStatusPublisher extends AggregateStatusPublisher {
    /** Fails this class's loading when the SCM's plugin isn't installed, so Jenkins skips the extension. */
    private static final Class<?> REQUIRES = BitbucketSCMSource.class;

    public static final String KEY = "jenkins-monorepo";

    @Override
    public boolean handles(SCMSource source) {
        return source instanceof BitbucketSCMSource;
    }

    @Override
    public String commit(SCMRevision revision) {
        if (revision instanceof PullRequestSCMRevision pull) {
            revision = pull.getPull();
        }
        return revision instanceof AbstractGitSCMSource.SCMRevisionImpl git ? git.getHash() : null;
    }

    @Override
    public void publish(SCMSource source, SCMRevision revision, String commit, State state, String description,
            String url, TaskListener listener) throws IOException, InterruptedException {
        BitbucketSCMSource bitbucket = (BitbucketSCMSource) source;
        if (!(revision.getHead() instanceof PullRequestSCMHead head)) {
            return;
        }
        EndpointType type = BitbucketEndpointProvider.lookupEndpoint(bitbucket.getServerUrl())
                .map(BitbucketEndpoint::getType)
                .orElseThrow(() -> new IOException("no Bitbucket endpoint for " + bitbucket.getServerUrl()));
        // As the plugin's own notifier does it: Cloud takes the status on the pull request's repository,
        // Data Center on the target repository
        boolean cloud = type == EndpointType.CLOUD;
        BitbucketApi client = cloud ? bitbucket.buildBitbucketClient(head) : bitbucket.buildBitbucketClient();
        BitbucketBuildStatusNotifier notifier = ExtensionList.lookup(BitbucketBuildStatusNotifier.class).stream()
                .filter(n -> n.isApplicable(type))
                .findFirst()
                .orElseThrow(() -> new IOException("no Bitbucket build status notifier for " + type));
        notifier.sendBuildStatus(status(head, commit, state, description, url, cloud),
                client.adapt(BitbucketAuthenticatedClient.class));
    }

    /**
     * The build status, as the plugin's own notifier would make it: on Cloud the ref is the pull
     * request's bare branch name, on Data Center its full ref.
     */
    static BitbucketBuildStatus status(PullRequestSCMHead head, String commit, State state, String description,
            String url, boolean cloud) {
        String ref = cloud ? head.getBranchName() : "refs/heads/" + head.getBranchName();
        BitbucketBuildStatus.Status status = switch (state) {
            case SUCCESS -> BitbucketBuildStatus.Status.SUCCESSFUL;
            case FAILURE -> BitbucketBuildStatus.Status.FAILED;
            case PENDING -> BitbucketBuildStatus.Status.INPROGRESS;
        };
        return new BitbucketBuildStatus(commit, description, status, url, KEY, "Monorepo", ref);
    }
}
