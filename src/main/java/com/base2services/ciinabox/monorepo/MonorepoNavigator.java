package com.base2services.ciinabox.monorepo;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMNavigatorDescriptor;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.SCMSourceObserver;
import jenkins.scm.api.SCMSourceOwner;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Turns one repository into the projects of an organization folder: one per Jenkinsfile found on
 * any of the repository's branches, named after the Jenkinsfile's directory. Each project gets a
 * copy of {@link #getSource() the source}, so branches and pull requests are discovered, built and
 * reported exactly as that SCM's own plugin does it.
 */
public class MonorepoNavigator extends SCMNavigator {
    static final String DEFAULT_JENKINSFILE = "Jenkinsfile";

    private final SCMSource source;
    private String jenkinsfile = DEFAULT_JENKINSFILE;

    @DataBoundConstructor
    public MonorepoNavigator(SCMSource source) {
        this.source = source;
    }

    public SCMSource getSource() {
        return source;
    }

    public String getJenkinsfile() {
        return jenkinsfile;
    }

    @DataBoundSetter
    public void setJenkinsfile(String jenkinsfile) {
        String name = Util.fixEmptyAndTrim(jenkinsfile);
        this.jenkinsfile = name == null ? DEFAULT_JENKINSFILE : name;
    }

    @NonNull
    @Override
    protected String id() {
        return "monorepo::" + source.getId();
    }

    /** The names of the projects the repository holds now. */
    Set<String> discover(@CheckForNull Item context, TaskListener listener) throws IOException, InterruptedException {
        SCMSource owned = source;
        if (source.getOwner() == null && context instanceof SCMSourceOwner owner) {
            // the configured source belongs to nothing; credentials are looked up from its owner
            owned = (SCMSource) Items.XSTREAM2.fromXML(Items.XSTREAM2.toXML(source));
            owned.setOwner(owner);
        }
        return MonorepoCache.of(owned, context).projects(jenkinsfile, listener);
    }

    @Override
    public void visitSources(@NonNull SCMSourceObserver observer) throws IOException, InterruptedException {
        TaskListener listener = observer.getListener();
        Item context = observer.getContext();
        Set<String> includes = observer.getIncludes();
        for (String name : discover(context, listener)) {
            if (!observer.isObserving()) {
                return;
            }
            if (includes != null && !includes.contains(name)) {
                continue;
            }
            listener.getLogger().println("Monorepo: " + Projects.scriptPath(name, jenkinsfile));
            SCMSourceObserver.ProjectObserver project = observer.observe(name);
            // Each project owns its copy. They keep the same id: ids only need to be unique in a project.
            SCMSource copy = (SCMSource) Items.XSTREAM2.fromXML(Items.XSTREAM2.toXML(source));
            for (ProjectSourceCustomizer customizer : ExtensionList.lookup(ProjectSourceCustomizer.class)) {
                customizer.customize(copy, name);
            }
            project.addSource(copy);
            project.complete();
        }
    }

    @Symbol("monorepo")
    @Extension
    public static class DescriptorImpl extends SCMNavigatorDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Monorepo";
        }

        @Override
        public String getDescription() {
            return "One repository, with a multibranch pipeline for each nested Jenkinsfile.";
        }

        @Override
        public SCMNavigator newInstance(@CheckForNull String name) {
            return null; // needs a repository, so it's added to an organization folder rather than created by name
        }

        /**
         * Every source: which of them check out with Git can't be told from the descriptor, so one
         * that doesn't fails the scan with a message saying so.
         */
        public List<SCMSourceDescriptor> getSourceDescriptors() {
            return new ArrayList<>(ExtensionList.lookup(SCMSourceDescriptor.class));
        }
    }
}
