package com.base2services.ciinabox.monorepo.gitlab;

import com.base2services.ciinabox.monorepo.AggregateStatusPublisher;
import com.base2services.ciinabox.monorepo.ProjectSourceCustomizer;
import hudson.Extension;
import hudson.model.TaskListener;
import io.jenkins.plugins.gitlabbranchsource.BuildStatusNameCustomPartTrait;
import io.jenkins.plugins.gitlabbranchsource.GitLabSCMSource;
import io.jenkins.plugins.gitlabbranchsource.MergeRequestSCMRevision;
import io.jenkins.plugins.gitlabbranchsource.helpers.GitLabHelper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.gitlab4j.api.Constants;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.CommitStatus;

/** GitLab: a status name per project, and the monorepo's overall status. */
public final class GitLabSupport {
    /** The overall status: a commit status with this name on the merge request's head commit. */
    public static final String NAME = "jenkinsci/monorepo";

    private GitLabSupport() {}

    /**
     * GitLab keeps one status per commit and name, and every project builds the same commits. The
     * plugin's own "build status name custom part" puts the project in the name:
     * {@code jenkinsci/services/api/mr-head}.
     */
    @Extension(optional = true)
    public static class Customizer extends ProjectSourceCustomizer {
        /** Fails this class's loading when the SCM's plugin isn't installed, so Jenkins skips the extension. */
        private static final Class<?> REQUIRES = GitLabSCMSource.class;

        @Override
        public void customize(SCMSource source, String project) {
            if (source instanceof GitLabSCMSource gitlab) {
                List<SCMSourceTrait> traits = new ArrayList<>(gitlab.getTraits());
                traits.removeIf(t -> t instanceof BuildStatusNameCustomPartTrait);
                BuildStatusNameCustomPartTrait name = new BuildStatusNameCustomPartTrait();
                name.setBuildStatusNameCustomPart(project);
                traits.add(name);
                gitlab.setTraits(traits);
            }
        }
    }

    @Extension(optional = true)
    public static class Publisher extends AggregateStatusPublisher {
        /** Fails this class's loading when the SCM's plugin isn't installed, so Jenkins skips the extension. */
        private static final Class<?> REQUIRES = GitLabSCMSource.class;

        @Override
        public boolean handles(SCMSource source) {
            return source instanceof GitLabSCMSource;
        }

        @Override
        public String commit(SCMRevision revision) {
            if (revision instanceof MergeRequestSCMRevision merge) {
                revision = merge.getOrigin();
            }
            return revision instanceof AbstractGitSCMSource.SCMRevisionImpl git ? git.getHash() : null;
        }

        @Override
        public void publish(SCMSource source, SCMRevision revision, String commit, State state, String description,
                String url, TaskListener listener) throws IOException {
            GitLabSCMSource gitlab = (GitLabSCMSource) source;
            CommitStatus status = status(revision, description, url);
            Constants.CommitBuildState gl = state(state);
            try {
                GitLabApi api = GitLabHelper.apiBuilder(gitlab.getOwner(), gitlab.getServerName(), gitlab.getCredentialsId());
                api.getCommitsApi().addCommitStatus(gitlab.getProjectPath(), commit, gl, status);
            } catch (GitLabApiException e) {
                // GitLab refuses to move a status to the state it is in: running, with a new count, is one
                if (e.getMessage() == null || !e.getMessage().contains("Cannot transition status")) {
                    throw new IOException(e);
                }
            }
        }

        /** The status, on the merge request's source branch so GitLab shows it on the merge request. */
        static CommitStatus status(SCMRevision revision, String description, String url) {
            CommitStatus status = new CommitStatus();
            status.setName(NAME);
            status.setTargetUrl(url);
            status.setDescription(description);
            if (revision instanceof MergeRequestSCMRevision merge) {
                status.setRef(merge.getOrigin().getHead().getName());
            }
            return status;
        }

        static Constants.CommitBuildState state(State state) {
            return switch (state) {
                case SUCCESS -> Constants.CommitBuildState.SUCCESS;
                case FAILURE -> Constants.CommitBuildState.FAILED;
                case PENDING -> Constants.CommitBuildState.RUNNING;
            };
        }
    }
}
