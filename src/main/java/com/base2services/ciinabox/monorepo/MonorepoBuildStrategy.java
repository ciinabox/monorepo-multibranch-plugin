package com.base2services.ciinabox.monorepo;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.TaskListener;
import java.util.Set;
import jenkins.branch.BranchBuildStrategy;
import jenkins.branch.BranchBuildStrategyDescriptor;
import jenkins.branch.MultiBranchProject;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.mixin.ChangeRequestSCMHead2;
import jenkins.scm.api.mixin.ChangeRequestSCMRevision;
import jenkins.scm.api.mixin.TagSCMHead;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Builds a branch or pull request in a monorepo project only when a path the project watches
 * changed (see {@link WatchRules}).
 *
 * <ul>
 * <li>A branch is compared with the revision this project last built on it; the first time, with
 *     where it left the base branch.</li>
 * <li>A pull request is compared with where it left its target, on every push.</li>
 * </ul>
 *
 * Whenever the answer can't be worked out, the build goes ahead.
 */
public class MonorepoBuildStrategy extends BranchBuildStrategy {
    private String baseBranch = "main";

    @DataBoundConstructor
    public MonorepoBuildStrategy() {}

    public String getBaseBranch() {
        return baseBranch;
    }

    @DataBoundSetter
    public void setBaseBranch(String baseBranch) {
        String name = Util.fixEmptyAndTrim(baseBranch);
        this.baseBranch = name == null ? "main" : name;
    }

    @Override
    public boolean isAutomaticBuild(@NonNull SCMSource source, @NonNull SCMHead head,
            @NonNull SCMRevision currRevision, @CheckForNull SCMRevision lastBuiltRevision,
            @CheckForNull SCMRevision lastSeenRevision, @NonNull TaskListener listener) {
        if (head instanceof TagSCMHead) {
            return false;
        }
        try {
            String skip = reasonToSkip(source, head, currRevision, lastBuiltRevision, listener);
            if (skip != null) {
                listener.getLogger().println("Monorepo: not building " + head.getName() + ": " + skip);
            }
            if (source.getOwner() instanceof MultiBranchProject<?, ?> project) {
                // A pull request that affects no project gets its overall status here: nothing will build
                AggregateStatus.update(project, source, currRevision, null, listener);
            }
            return skip == null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Exception e) {
            listener.getLogger().println("Monorepo: building " + head.getName() + ", can't tell what changed: " + e);
            return true;
        }
    }

    /** Null to build, with the reason logged; otherwise why not. */
    @CheckForNull
    private String reasonToSkip(SCMSource source, SCMHead head, SCMRevision current,
            @CheckForNull SCMRevision lastBuilt, TaskListener listener) throws Exception {
        if (!(source.getOwner() instanceof MultiBranchProject<?, ?> project)) {
            return null;
        }
        String dir = Projects.dirOf(Projects.nameOf(project));
        MonorepoCache cache = MonorepoCache.of(source, project);
        String from;
        String to;
        if (current instanceof ChangeRequestSCMRevision<?> change) {
            if (head.getOrigin() != SCMHeadOrigin.DEFAULT) {
                return build(listener, head, "a pull request from another repository");
            }
            // Its head commit, as the SCM's own plugin knows it: the same one the overall status is about.
            // A generic revision doesn't say, so without that it's where its branch in the repository is.
            AggregateStatusPublisher commits = AggregateStatus.publisher(source);
            to = commits == null ? null : commits.commit(current);
            if (to == null && head instanceof ChangeRequestSCMHead2 request) {
                to = cache.branch(request.getOriginName(), listener);
            }
            to = cache.full(to, listener); // some SCMs shorten it
            String target = hash(change.getTarget());
            from = to == null || target == null ? null : cache.mergeBase(target, to, listener);
        } else {
            to = hash(current);
            if (lastBuilt != null) {
                from = hash(lastBuilt);
            } else {
                String base = cache.branch(baseBranch, listener);
                if (base == null || base.equals(to)) {
                    return build(listener, head, "its first build");
                }
                from = to == null ? null : cache.mergeBase(base, to, listener);
            }
        }
        Set<String> changed = from == null || to == null ? null : cache.changedPaths(from, to, listener);
        if (changed == null) {
            return build(listener, head, "the commits to compare aren't known");
        }
        String commit = to;
        Set<String> patterns = WatchRules.patterns(dir, path -> cache.read(commit, path, listener));
        String match = WatchRules.firstMatch(patterns, changed);
        if (match == null) {
            return "none of " + changed.size() + " changed paths (" + from.substring(0, 7) + ".." + to.substring(0, 7)
                    + ") match " + patterns;
        }
        return build(listener, head, match + " changed");
    }

    private static String build(TaskListener listener, SCMHead head, String why) {
        listener.getLogger().println("Monorepo: building " + head.getName() + ": " + why);
        return null;
    }

    @CheckForNull
    private static String hash(SCMRevision revision) {
        return revision instanceof AbstractGitSCMSource.SCMRevisionImpl git ? git.getHash() : null;
    }

    @Symbol("monorepoChangedPaths")
    @Extension
    public static class DescriptorImpl extends BranchBuildStrategyDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Monorepo: build when watched paths change";
        }
    }
}
