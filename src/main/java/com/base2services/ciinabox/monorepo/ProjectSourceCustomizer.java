package com.base2services.ciinabox.monorepo;

import hudson.ExtensionPoint;
import jenkins.scm.api.SCMSource;

/**
 * Adjusts a project's copy of the monorepo's source. Every project builds the same commits, so
 * whatever an SCM keys on the commit alone (a commit status context, say) has to be made the
 * project's own here. An SCM's plugin can implement this itself, with an optional dependency on
 * this one; this plugin has implementations for GitHub, Bitbucket and GitLab.
 */
public abstract class ProjectSourceCustomizer implements ExtensionPoint {
    /**
     * @param source the project's own copy, not yet attached to it
     * @param project the project's name: the directory of its Jenkinsfile
     */
    public abstract void customize(SCMSource source, String project);
}
