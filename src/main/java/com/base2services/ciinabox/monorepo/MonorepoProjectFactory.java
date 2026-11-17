package com.base2services.ciinabox.monorepo;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.ItemGroup;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import jenkins.branch.MultiBranchProject;
import jenkins.branch.MultiBranchProjectFactory;
import jenkins.branch.MultiBranchProjectFactoryDescriptor;
import jenkins.branch.NameEncoder;
import jenkins.branch.OrganizationFolder;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Makes each project a {@link MonorepoNavigator} finds a multibranch pipeline whose script path is
 * the Jenkinsfile in that project's directory. A branch then belongs to a project only if it has
 * that Jenkinsfile.
 */
public class MonorepoProjectFactory extends MultiBranchProjectFactory {

    @DataBoundConstructor
    public MonorepoProjectFactory() {}

    @CheckForNull
    static MonorepoNavigator navigator(ItemGroup<?> parent) {
        if (parent instanceof OrganizationFolder folder) {
            for (SCMNavigator navigator : folder.getSCMNavigators()) {
                if (navigator instanceof MonorepoNavigator monorepo) {
                    return monorepo;
                }
            }
        }
        return null;
    }

    @Override
    public boolean recognizes(@NonNull ItemGroup<?> parent, @NonNull String name,
            @NonNull List<? extends SCMSource> scmSources, @NonNull Map<String, Object> attributes,
            @CheckForNull SCMHeadEvent<?> event, @NonNull TaskListener listener) {
        return navigator(parent) != null; // the navigator only reports directories that have a Jenkinsfile
    }

    @NonNull
    @Override
    public MultiBranchProject<?, ?> createNewProject(@NonNull ItemGroup<?> parent, @NonNull String name,
            @NonNull List<? extends SCMSource> scmSources, @NonNull Map<String, Object> attributes,
            @NonNull TaskListener listener) throws IOException {
        WorkflowMultiBranchProject project = new WorkflowMultiBranchProject(parent, name);
        configure(project, NameEncoder.decode(name), navigator(parent));
        return project;
    }

    @Override
    public void updateExistingProject(@NonNull MultiBranchProject<?, ?> project,
            @NonNull Map<String, Object> attributes, @NonNull TaskListener listener) throws IOException {
        if (project instanceof WorkflowMultiBranchProject workflow) {
            configure(workflow, Projects.nameOf(project), navigator(project.getParent()));
        }
    }

    private static void configure(WorkflowMultiBranchProject project, String name,
            @CheckForNull MonorepoNavigator navigator) {
        String jenkinsfile = navigator == null ? MonorepoNavigator.DEFAULT_JENKINSFILE : navigator.getJenkinsfile();
        WorkflowBranchProjectFactory factory = new WorkflowBranchProjectFactory();
        factory.setScriptPath(Projects.scriptPath(name, jenkinsfile));
        project.setProjectFactory(factory);
    }

    @Symbol("monorepoProjects")
    @Extension
    public static class DescriptorImpl extends MultiBranchProjectFactoryDescriptor {
        @Override
        public MultiBranchProjectFactory newInstance() {
            return null; // not a default: only folders with a Monorepo source use it
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Monorepo: a pipeline per nested Jenkinsfile";
        }
    }
}
