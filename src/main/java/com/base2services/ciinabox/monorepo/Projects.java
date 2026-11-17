package com.base2services.ciinabox.monorepo;

import jenkins.branch.MultiBranchProject;
import jenkins.branch.NameEncoder;
import jenkins.branch.ProjectNameProperty;

/**
 * A project's name is the directory that holds its Jenkinsfile. An organization folder hands a
 * project factory nothing but the name, so the name is what carries the path.
 */
final class Projects {
    /** The project for a Jenkinsfile at the top of the repository. */
    static final String ROOT = "(root)";

    private Projects() {}

    /** {@code services/api/Jenkinsfile} is project {@code services/api}. */
    static String nameFor(String jenkinsfilePath) {
        int slash = jenkinsfilePath.lastIndexOf('/');
        return slash < 0 ? ROOT : jenkinsfilePath.substring(0, slash);
    }

    /** The project's directory from the repository root; empty for the root project. */
    static String dirOf(String name) {
        return ROOT.equals(name) ? "" : name;
    }

    static String scriptPath(String name, String jenkinsfile) {
        String dir = dirOf(name);
        return dir.isEmpty() ? jenkinsfile : dir + "/" + jenkinsfile;
    }

    static String nameOf(MultiBranchProject<?, ?> project) {
        ProjectNameProperty property = project.getProperties().get(ProjectNameProperty.class);
        return property != null ? property.getName() : NameEncoder.decode(project.getName());
    }
}
