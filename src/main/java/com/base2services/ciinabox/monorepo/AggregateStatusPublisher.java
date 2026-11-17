package com.base2services.ciinabox.monorepo;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionPoint;
import hudson.model.TaskListener;
import java.io.IOException;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;

/**
 * Reports one status for a pull request across every project it affects, so branch protection can
 * require a single check whichever projects a pull request touches. How a commit gets a status is
 * the SCM's business: an SCM's plugin can implement this itself, with an optional dependency on
 * this one; this plugin has implementations for GitHub, Bitbucket and GitLab.
 */
public abstract class AggregateStatusPublisher implements ExtensionPoint {
    public enum State { PENDING, SUCCESS, FAILURE }

    public abstract boolean handles(SCMSource source);

    /** The commit a pull request revision's status belongs on (its head commit), or null. */
    @CheckForNull
    public abstract String commit(SCMRevision revision);

    /**
     * @param revision the pull request revision the status is about
     * @param commit {@link #commit(SCMRevision)} of that revision
     */
    public abstract void publish(SCMSource source, SCMRevision revision, String commit, State state,
            String description, String url, TaskListener listener) throws IOException, InterruptedException;
}
