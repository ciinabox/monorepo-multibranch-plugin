package com.base2services.ciinabox.monorepo;

import hudson.Extension;
import hudson.model.Cause;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.branch.MultiBranchProject;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMEventListener;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMSource;
import jenkins.util.Timer;

/**
 * A push reaches the projects that already exist, not the folder. When one adds the first
 * Jenkinsfile of a directory, or removes the last, this rescans the folder so the project appears
 * or goes.
 */
@Extension
public class MonorepoEventListener extends SCMEventListener {
    private static final Logger LOGGER = Logger.getLogger(MonorepoEventListener.class.getName());

    @Override
    public void onSCMHeadEvent(SCMHeadEvent<?> event) {
        Timer.get().submit(() -> {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                for (OrganizationFolder folder : Jenkins.get().getAllItems(OrganizationFolder.class)) {
                    for (SCMNavigator navigator : folder.getSCMNavigators()) {
                        if (navigator instanceof MonorepoNavigator monorepo && concerns(folder, monorepo, event)) {
                            rescanIfChanged(folder, monorepo);
                        }
                    }
                }
            }
        });
    }

    /**
     * Whether an event is about a folder's repository. Some SCMs match an event on what a source
     * learns when it is first used (GitLab compares project IDs), which the folder's own source never
     * is: the projects' copies have been, so they're asked too.
     */
    static boolean concerns(OrganizationFolder folder, MonorepoNavigator navigator, SCMHeadEvent<?> event) {
        if (event.isMatch(navigator.getSource())) {
            return true;
        }
        for (MultiBranchProject<?, ?> project : folder.getItems()) {
            for (SCMSource source : project.getSCMSources()) {
                if (event.isMatch(source)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void rescanIfChanged(OrganizationFolder folder, MonorepoNavigator navigator) {
        try {
            Set<String> found = navigator.discover(folder, TaskListener.NULL);
            Set<String> existing = new TreeSet<>();
            for (MultiBranchProject<?, ?> project : folder.getItems()) {
                existing.add(Projects.nameOf(project));
            }
            if (!found.equals(existing)) {
                LOGGER.fine(() -> folder.getFullName() + ": projects " + existing + " -> " + found);
                folder.scheduleBuild(0, new Cause.RemoteCause("monorepo", "the repository's Jenkinsfiles changed"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, folder.getFullName() + ": can't look for new Jenkinsfiles", e);
        }
    }
}
