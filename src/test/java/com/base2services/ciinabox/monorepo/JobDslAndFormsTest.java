package com.base2services.ciinabox.monorepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import javaposse.jobdsl.plugin.ExecuteDslScripts;
import javaposse.jobdsl.plugin.RemovedJobAction;
import jenkins.branch.OrganizationFolder;
import jenkins.plugins.git.GitSCMSource;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The names people configure this by: the Job DSL symbols, and the forms. */
@WithJenkins
class JobDslAndFormsTest {

    @Test
    void jobDslSymbols(JenkinsRule j) throws Exception {
        hudson.model.FreeStyleProject seed = j.createFreeStyleProject("seed");
        ExecuteDslScripts dsl = new ExecuteDslScripts();
        dsl.setScriptText("""
                organizationFolder('mono') {
                  organizations {
                    monorepo {
                      source {
                        gitSCMSource {
                          id('mono')
                          remote('https://example.invalid/mono.git')
                        }
                      }
                      jenkinsfile('Build.groovy')
                    }
                  }
                  projectFactories {
                    monorepoProjects()
                  }
                  buildStrategies {
                    monorepoChangedPaths { baseBranch('trunk') }
                  }
                }
                """);
        dsl.setUseScriptText(true);
        dsl.setRemovedJobAction(RemovedJobAction.IGNORE);
        dsl.setSandbox(false);
        seed.getBuildersList().add(dsl);
        j.buildAndAssertSuccess(seed);

        OrganizationFolder folder = j.jenkins.getItemByFullName("mono", OrganizationFolder.class);
        MonorepoNavigator navigator = assertInstanceOf(MonorepoNavigator.class, folder.getSCMNavigators().get(0));
        assertEquals("Build.groovy", navigator.getJenkinsfile());
        assertEquals("https://example.invalid/mono.git", ((GitSCMSource) navigator.getSource()).getRemote());
        assertInstanceOf(MonorepoProjectFactory.class, folder.getProjectFactories().get(0));
        assertEquals("trunk", folder.getBuildStrategies().get(MonorepoBuildStrategy.class).getBaseBranch());
    }

    @Test
    void theFormsKeepWhatWasConfigured(JenkinsRule j) throws Exception {
        OrganizationFolder folder = j.jenkins.createProject(OrganizationFolder.class, "mono");
        MonorepoNavigator navigator = new MonorepoNavigator(new GitSCMSource("https://example.invalid/mono.git"));
        navigator.setJenkinsfile("Build.groovy");
        folder.getSCMNavigators().add(navigator);
        folder.getProjectFactories().clear();
        folder.getProjectFactories().add(new MonorepoProjectFactory());
        MonorepoBuildStrategy strategy = new MonorepoBuildStrategy();
        strategy.setBaseBranch("trunk");
        folder.getBuildStrategies().add(strategy);

        j.configRoundtrip(folder);

        MonorepoNavigator after = assertInstanceOf(MonorepoNavigator.class, folder.getSCMNavigators().get(0));
        assertEquals("Build.groovy", after.getJenkinsfile());
        assertEquals("https://example.invalid/mono.git", ((GitSCMSource) after.getSource()).getRemote());
        assertInstanceOf(MonorepoProjectFactory.class, folder.getProjectFactories().get(0));
        assertEquals("trunk", folder.getBuildStrategies().get(MonorepoBuildStrategy.class).getBaseBranch());
    }
}
