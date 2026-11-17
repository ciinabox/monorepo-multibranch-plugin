package com.base2services.ciinabox.monorepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import jenkins.branch.MultiBranchProject;
import jenkins.branch.OrganizationFolder;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class MonorepoFolderTest {
    @TempDir
    Path repo;

    private JenkinsRule j;
    private OrganizationFolder folder;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        MonorepoCache.fetchWindowMillis = 0;
        git("init", "-q", "-b", "main");
        git("config", "user.name", "test");
        git("config", "user.email", "test@example.invalid");
        git("config", "commit.gpgsign", "false");
        write("README.md", "monorepo");
        write("services/api/Jenkinsfile", "echo 'api'");
        write("services/api/monorepo.yaml", "dependsOn: [libs/common]\nwatch: ['shared/schemas/**']\n");
        write("services/web/Jenkinsfile", "echo 'web'");
        write("libs/common/Jenkinsfile", "echo 'common'");
        commit("initial");

        GitSCMSource source = new GitSCMSource(repo.toString());
        source.setTraits(List.of(new BranchDiscoveryTrait()));
        folder = j.jenkins.createProject(OrganizationFolder.class, "mono");
        folder.getSCMNavigators().add(new MonorepoNavigator(source));
        folder.getProjectFactories().clear();
        folder.getProjectFactories().add(new MonorepoProjectFactory());
        folder.getBuildStrategies().add(new MonorepoBuildStrategy());
        scanFolder();
    }

    @Test
    void aProjectPerJenkinsfileEachWithItsOwnScriptPath() throws Exception {
        assertEquals(Set.of("libs/common", "services/api", "services/web"), projectNames());
        WorkflowMultiBranchProject api = project("services/api");
        assertEquals("services/api/Jenkinsfile",
                ((org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory) api.getProjectFactory())
                        .getScriptPath());
        for (String name : projectNames()) {
            assertEquals(1, builds(name, "main"), name + " builds main once when first found");
        }
        j.assertLogContains("api", branch("services/api", "main").getLastBuild());
    }

    @Test
    void theControllerCloneRunsNoHooks() throws Exception {
        java.io.File[] caches = new java.io.File(j.jenkins.getRootDir(), "caches").listFiles((d, n) -> n.startsWith("monorepo-"));
        assertEquals(1, caches.length);
        String config = Files.readString(caches[0].toPath().resolve(".git/config"));
        assertEquals(true, config.contains("hooksPath = /dev/null"), config);
    }

    @Test
    void onlyAffectedProjectsBuild() throws Exception {
        write("services/web/index.html", "hello");
        commit("web only");
        scanProjects();
        assertEquals(2, builds("services/web", "main"));
        assertEquals(1, builds("services/api", "main"));
        assertEquals(1, builds("libs/common", "main"));

        write("README.md", "nobody watches this");
        commit("docs");
        scanProjects();
        assertEquals(2, builds("services/web", "main"));
        assertEquals(1, builds("services/api", "main"));
        assertEquals(1, builds("libs/common", "main"));
    }

    @Test
    void dependsOnAndWatchRebuildTheDependant() throws Exception {
        write("libs/common/lib.txt", "v2");
        commit("common");
        scanProjects();
        assertEquals(2, builds("libs/common", "main"));
        assertEquals(2, builds("services/api", "main"), "api depends on libs/common");
        assertEquals(1, builds("services/web", "main"));

        write("shared/schemas/a.json", "{}");
        commit("schema");
        scanProjects();
        assertEquals(3, builds("services/api", "main"), "api watches shared/schemas");
        assertEquals(2, builds("libs/common", "main"));
        assertEquals(1, builds("services/web", "main"));
    }

    @Test
    void skippedCommitsStillCountOnceAProjectIsAffected() throws Exception {
        // The comparison is with the last revision the project built, not the last one it saw.
        write("services/web/a.txt", "1");
        commit("web 1");
        write("README.md", "later, unrelated");
        commit("docs");
        scanProjects();
        assertEquals(2, builds("services/web", "main"));
    }

    @Test
    void aNewBranchBuildsOnlyWhatItChanged() throws Exception {
        git("checkout", "-q", "-b", "feature");
        write("services/web/feature.txt", "x");
        commit("feature");
        scanProjects();
        assertEquals(1, builds("services/web", "feature"));
        assertNotNull(branch("services/api", "feature"), "the branch exists in every project that has a Jenkinsfile");
        assertEquals(0, builds("services/api", "feature"));
        assertEquals(0, builds("libs/common", "feature"));
    }

    @Test
    void aJenkinsfileOnABranchCreatesAProject() throws Exception {
        git("checkout", "-q", "-b", "feature");
        write("tools/release/Jenkinsfile", "echo 'release'");
        commit("new project");
        MonorepoEventListener.rescanIfChanged(folder, (MonorepoNavigator) folder.getSCMNavigators().get(0));
        j.waitUntilNoActivity();
        assertEquals(Set.of("libs/common", "services/api", "services/web", "tools/release"), projectNames());
        assertEquals(1, builds("tools/release", "feature"));
        assertNull(branch("tools/release", "main"), "main has no Jenkinsfile there");
    }

    @Test
    void anEventMatchedOnlyByAProjectsSourceStillConcernsTheFolder() throws Exception {
        MonorepoNavigator navigator = (MonorepoNavigator) folder.getSCMNavigators().get(0);
        // Like GitLab's: only a source that has been used (here, one with an owner) matches
        jenkins.scm.api.SCMHeadEvent<Object> event = new jenkins.scm.api.SCMHeadEvent<>(
                jenkins.scm.api.SCMEvent.Type.UPDATED, new Object(), "test") {
            @Override public boolean isMatch(jenkins.scm.api.SCMNavigator n) { return false; }
            @Override public String getSourceName() { return "mono"; }
            @Override public java.util.Map<jenkins.scm.api.SCMHead, jenkins.scm.api.SCMRevision> heads(jenkins.scm.api.SCMSource s) {
                return java.util.Map.of();
            }
            @Override public boolean isMatch(jenkins.scm.api.SCMSource s) { return s.getOwner() != null; }
            @Override public boolean isMatch(hudson.scm.SCM scm) { return false; }
        };
        assertEquals(false, event.isMatch(navigator.getSource()));
        assertEquals(true, MonorepoEventListener.concerns(folder, navigator, event));
    }

    private void scanFolder() throws Exception {
        folder.scheduleBuild2(0).getFuture().get();
        j.waitUntilNoActivity();
    }

    private void scanProjects() throws Exception {
        for (MultiBranchProject<?, ?> project : folder.getItems()) {
            project.scheduleBuild2(0).getFuture().get();
        }
        j.waitUntilNoActivity();
    }

    private Set<String> projectNames() {
        Set<String> names = new TreeSet<>();
        for (MultiBranchProject<?, ?> project : folder.getItems()) {
            names.add(Projects.nameOf(project));
        }
        return names;
    }

    private WorkflowMultiBranchProject project(String name) {
        for (MultiBranchProject<?, ?> project : folder.getItems()) {
            if (Projects.nameOf(project).equals(name)) {
                return (WorkflowMultiBranchProject) project;
            }
        }
        throw new AssertionError("no project " + name + " in " + projectNames());
    }

    private WorkflowJob branch(String project, String branch) {
        return project(project).getItem(branch);
    }

    private int builds(String project, String branch) {
        WorkflowJob job = branch(project, branch);
        return job == null ? 0 : job.getBuilds().size();
    }

    private void write(String path, String text) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private void commit(String message) throws Exception {
        git("add", "-A");
        git("commit", "-q", "-m", message);
    }

    private void git(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(new File(repo.toString())).inheritIO().start();
        if (p.waitFor() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed");
        }
    }
}
