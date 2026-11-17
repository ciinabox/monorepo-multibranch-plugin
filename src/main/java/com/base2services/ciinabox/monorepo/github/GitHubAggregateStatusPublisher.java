package com.base2services.ciinabox.monorepo.github;

import com.base2services.ciinabox.monorepo.AggregateStatusPublisher;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.Extension;
import hudson.model.TaskListener;
import java.io.IOException;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.plugins.github_branch_source.Connector;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GitHub;

/** The monorepo's overall status on GitHub: {@value #CONTEXT} on the pull request's head commit. */
@Extension(optional = true)
public class GitHubAggregateStatusPublisher extends AggregateStatusPublisher {
    /** Fails this class's loading when the SCM's plugin isn't installed, so Jenkins skips the extension. */
    private static final Class<?> REQUIRES = GitHubSCMSource.class;

    public static final String CONTEXT = "continuous-integration/jenkins/monorepo";

    @Override
    public boolean handles(SCMSource source) {
        return source instanceof GitHubSCMSource;
    }

    @Override
    public String commit(SCMRevision revision) {
        if (revision instanceof PullRequestSCMRevision pull) {
            return pull.getPullHash();
        }
        return revision instanceof AbstractGitSCMSource.SCMRevisionImpl git ? git.getHash() : null;
    }

    @Override
    public void publish(SCMSource source, SCMRevision revision, String commit, State state, String description,
            String url, TaskListener listener) throws IOException {
        GitHubSCMSource github = (GitHubSCMSource) source;
        StandardCredentials credentials = Connector.lookupScanCredentials(
                github.getOwner(), github.getApiUri(), github.getCredentialsId(), github.getRepoOwner());
        GitHub hub = Connector.connect(github.getApiUri(), credentials);
        try {
            GHCommitState gh = switch (state) {
                case SUCCESS -> GHCommitState.SUCCESS;
                case FAILURE -> GHCommitState.FAILURE;
                case PENDING -> GHCommitState.PENDING;
            };
            String text = description.length() > 140 ? description.substring(0, 137) + "..." : description;
            hub.getRepository(github.getRepoOwner() + "/" + github.getRepository())
                    .createCommitStatus(commit, gh, url, text, CONTEXT);
        } finally {
            Connector.release(hub);
        }
    }
}
