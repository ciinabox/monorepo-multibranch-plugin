package com.base2services.ciinabox.monorepo.github;

import com.base2services.ciinabox.monorepo.ProjectSourceCustomizer;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.TaskListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceContext;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.github_branch_source.AbstractGitHubNotificationStrategy;
import org.jenkinsci.plugins.github_branch_source.GitHubNotificationContext;
import org.jenkinsci.plugins.github_branch_source.GitHubNotificationRequest;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSourceContext;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Gives a monorepo project its own GitHub commit status context. GitHub keeps one status per
 * commit and context, and every project builds the same commits, so with the default context
 * ({@code continuous-integration/jenkins/pr-head}) the projects overwrite each other. This puts the
 * project in it: {@code continuous-integration/jenkins/services/api/pr-head}.
 *
 * <p>{@link Customizer} adds it to each project's source; it isn't meant to be configured by hand.
 */
public class GitHubProjectStatusContextTrait extends SCMSourceTrait {
    private final String project;

    @DataBoundConstructor
    public GitHubProjectStatusContextTrait(String project) {
        this.project = project;
    }

    public String getProject() {
        return project;
    }

    @Override
    protected void decorateContext(SCMSourceContext<?, ?> context) {
        if (context instanceof GitHubSCMSourceContext github) {
            github.withNotificationStrategies(List.of(new Strategy(project)));
        }
    }

    /** {@code continuous-integration/jenkins/pr-head} for {@code services/api} is {@code continuous-integration/jenkins/services/api/pr-head}. */
    static String contextFor(String defaultContext, String project) {
        int slash = defaultContext.lastIndexOf('/');
        return slash < 0
                ? project + "/" + defaultContext
                : defaultContext.substring(0, slash) + "/" + project + defaultContext.substring(slash);
    }

    static final class Strategy extends AbstractGitHubNotificationStrategy {
        private final String project;

        Strategy(String project) {
            this.project = project;
        }

        @Override
        public List<GitHubNotificationRequest> notifications(GitHubNotificationContext context, TaskListener listener) {
            return List.of(GitHubNotificationRequest.build(
                    contextFor(context.getDefaultContext(listener), project),
                    context.getDefaultUrl(listener),
                    context.getDefaultMessage(listener),
                    context.getDefaultState(listener),
                    context.getDefaultIgnoreError(listener)));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Strategy other && Objects.equals(project, other.project);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(project);
        }
    }

    @Extension(optional = true)
    public static class Customizer extends ProjectSourceCustomizer {
        /** Fails this class's loading when the SCM's plugin isn't installed, so Jenkins skips the extension. */
        private static final Class<?> REQUIRES = GitHubSCMSource.class;

        @Override
        public void customize(SCMSource source, String project) {
            if (source instanceof GitHubSCMSource github) {
                List<SCMSourceTrait> traits = new ArrayList<>(github.getTraits());
                traits.removeIf(t -> t instanceof GitHubProjectStatusContextTrait);
                traits.add(new GitHubProjectStatusContextTrait(project));
                github.setTraits(traits);
            }
        }
    }

    @Symbol("monorepoProjectStatusContext")
    @Extension(optional = true)
    public static class DescriptorImpl extends SCMSourceTraitDescriptor {
        /** Fails this class's loading when the SCM's plugin isn't installed, so Jenkins skips the extension. */
        private static final Class<?> REQUIRES = GitHubSCMSource.class;

        @NonNull
        @Override
        public String getDisplayName() {
            return "Monorepo: a commit status context per project";
        }

        @Override
        public Class<? extends SCMSourceContext> getContextClass() {
            return GitHubSCMSourceContext.class;
        }

        @Override
        public Class<? extends SCMSource> getSourceClass() {
            return GitHubSCMSource.class;
        }
    }
}
