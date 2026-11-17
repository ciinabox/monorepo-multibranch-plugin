package com.base2services.ciinabox.monorepo;

import com.base2services.ciinabox.monorepo.AggregateStatusPublisher.State;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.branch.MultiBranchProject;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.mixin.ChangeRequestSCMHead;
import jenkins.scm.api.mixin.ChangeRequestSCMRevision;
import jenkins.util.Timer;

/**
 * One status per pull request commit: pending until every project the pull request affects has
 * built that commit, then success or failure. A pull request that affects no project succeeds at
 * once, which is what lets the status be a required check.
 *
 * <p>Which projects are affected is worked out from the repository itself, not from which projects
 * have got round to looking at the commit, so the status can't go green early.
 */
final class AggregateStatus {
    private static final Logger LOGGER = Logger.getLogger(AggregateStatus.class.getName());
    /** What was last published per commit, so N projects deciding on one push publish once. */
    private static final Map<String, String> PUBLISHED = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> AFFECTED = new ConcurrentHashMap<>();

    record Summary(State state, String description) {}

    private AggregateStatus() {}

    /** @param projects the state of each affected project */
    static Summary summarise(Map<String, State> projects) {
        if (projects.isEmpty()) {
            return new Summary(State.SUCCESS, "No projects affected");
        }
        List<String> failed = new ArrayList<>();
        int pending = 0;
        for (Map.Entry<String, State> e : projects.entrySet()) {
            if (e.getValue() == State.FAILURE) {
                failed.add(e.getKey());
            } else if (e.getValue() == State.PENDING) {
                pending++;
            }
        }
        int n = projects.size();
        String affected = n + " affected project" + (n == 1 ? "" : "s");
        if (!failed.isEmpty()) {
            return new Summary(State.FAILURE, "Failed: " + String.join(", ", failed) + " (" + affected + ")");
        }
        if (pending > 0) {
            return new Summary(State.PENDING, (n - pending) + " of " + affected + " finished");
        }
        return new Summary(State.SUCCESS, affected + " passed");
    }

    @CheckForNull
    static AggregateStatusPublisher publisher(SCMSource source) {
        for (AggregateStatusPublisher p : ExtensionList.lookup(AggregateStatusPublisher.class)) {
            if (p.handles(source)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Recomputes and publishes the status for a pull request revision.
     *
     * @param completed a build that has just finished, though Jenkins may still call it building
     */
    static void update(MultiBranchProject<?, ?> project, SCMSource source, SCMRevision revision,
            @CheckForNull Run<?, ?> completed, TaskListener listener) {
        try {
            SCMHead head = revision.getHead();
            AggregateStatusPublisher publisher = publisher(source);
            if (!(head instanceof ChangeRequestSCMHead) || publisher == null
                    || !(project.getParent() instanceof OrganizationFolder folder)) {
                return;
            }
            MonorepoNavigator navigator = MonorepoProjectFactory.navigator(folder);
            String reported = publisher.commit(revision);
            if (navigator == null || reported == null) {
                return;
            }
            // Some SCMs shorten a pull request's commit until it has been built; the status goes on the
            // full one when the clone knows it, so both spellings land on one status
            String full = MonorepoCache.of(source, project).full(reported, listener);
            String commit = full == null ? reported : full;
            Set<String> affected = affected(project, source, revision, commit, navigator.getJenkinsfile(), listener);
            Map<String, State> states = new TreeMap<>();
            for (MultiBranchProject<?, ?> p : folder.getItems()) {
                String name = Projects.nameOf(p);
                Job<?, ?> job = p.getItemByBranchName(head.getName());
                // When the repository can't say what's affected: every project that has the pull request
                if (affected == null ? job == null : !affected.contains(name)) {
                    continue;
                }
                states.put(name, state(p, job, commit, publisher, completed));
            }
            if (affected != null) {
                for (String name : affected) {
                    states.putIfAbsent(name, State.PENDING); // a Jenkinsfile the folder hasn't found yet
                }
            }
            Summary summary = summarise(states);
            String key = folder.getFullName() + "@" + commit;
            String value = summary.state() + " " + summary.description();
            if (value.equals(PUBLISHED.get(key))) {
                return;
            }
            String root = Jenkins.get().getRootUrl();
            publisher.publish(source, revision, commit, summary.state(), summary.description(),
                    (root == null ? "" : root) + folder.getUrl(), listener);
            if (PUBLISHED.size() > 2000) {
                PUBLISHED.clear();
            }
            PUBLISHED.put(key, value);
            listener.getLogger().println("Monorepo: " + head.getName() + " overall " + value + " " + states);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "can't publish the monorepo status for " + revision, e);
            listener.getLogger().println("Monorepo: can't publish the overall status: " + e);
        }
    }

    /** The projects a pull request affects at a commit, or null when the repository clone can't say. */
    @CheckForNull
    private static Set<String> affected(MultiBranchProject<?, ?> project, SCMSource source, SCMRevision revision,
            String commit, String jenkinsfile, TaskListener listener) throws Exception {
        if (!(revision instanceof ChangeRequestSCMRevision<?> change)
                || !(change.getTarget() instanceof AbstractGitSCMSource.SCMRevisionImpl target)) {
            return null;
        }
        MonorepoCache cache = MonorepoCache.of(source, project);
        // The answer for a commit and target never changes, and every project and build asks for it
        String key = cache.remote() + " " + commit + " " + target.getHash() + " " + jenkinsfile;
        Set<String> known = AFFECTED.get(key);
        if (known != null) {
            return known;
        }
        String base = cache.mergeBase(target.getHash(), commit, listener);
        Set<String> changed = base == null ? null : cache.changedPaths(base, commit, listener);
        Set<String> projects = changed == null ? null : cache.projectsAt(commit, jenkinsfile, listener);
        if (projects == null) {
            return null;
        }
        Set<String> affected = new TreeSet<>();
        for (String name : projects) {
            try {
                Set<String> patterns = WatchRules.patterns(Projects.dirOf(name), path -> cache.read(commit, path, listener));
                if (WatchRules.firstMatch(patterns, changed) != null) {
                    affected.add(name);
                }
            } catch (IllegalArgumentException | org.yaml.snakeyaml.error.YAMLException e) {
                affected.add(name); // a monorepo.yaml that can't be read builds, like the build strategy
            }
        }
        if (AFFECTED.size() > 500) {
            AFFECTED.clear();
        }
        AFFECTED.put(key, affected);
        return affected;
    }

    private static State state(MultiBranchProject<?, ?> project, @CheckForNull Job<?, ?> job, String commit,
            AggregateStatusPublisher publisher, @CheckForNull Run<?, ?> completed) {
        Run<?, ?> build = job == null ? null : job.getLastBuild();
        if (build == null) {
            return State.PENDING;
        }
        SCMRevisionAction action = build.getAction(SCMRevisionAction.class);
        String built = action == null ? null : publisher.commit(action.getRevision());
        if (built == null || !(built.startsWith(commit) || commit.startsWith(built))) {
            return State.PENDING; // its last build is of an earlier commit
        }
        if (build != completed && build.isBuilding()) {
            return State.PENDING;
        }
        return build.getResult() == Result.SUCCESS ? State.SUCCESS : State.FAILURE;
    }

    @Extension
    public static class Builds extends RunListener<Run<?, ?>> {
        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            later(run, null);
        }

        @Override
        public void onCompleted(Run<?, ?> run, TaskListener listener) {
            later(run, run);
        }

        private static void later(Run<?, ?> run, @CheckForNull Run<?, ?> completed) {
            SCMRevisionAction action = run.getAction(SCMRevisionAction.class);
            if (action == null || !(run.getParent().getParent() instanceof MultiBranchProject<?, ?> project)
                    || MonorepoProjectFactory.navigator(project.getParent()) == null) {
                return;
            }
            SCMSource source = project.getSCMSource(action.getSourceId());
            if (source == null) {
                return;
            }
            Timer.get().submit(() -> {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    update(project, source, action.getRevision(), completed, TaskListener.NULL);
                }
            });
        }
    }
}
