# Monorepo Multibranch

A Jenkins plugin that turns one monorepo into an organization folder: a multibranch pipeline for
each nested `Jenkinsfile`, built only when the paths it watches change.

It works with any branch source that checks out with Git. Each project gets its own commit status
and pull requests get one overall status on GitHub, Bitbucket and GitLab; other SCMs' plugins can
add the same through two extension points.

## How it works

- **Monorepo** (an organization folder source) reads one Git repository through any branch source
  that checks out with Git (GitHub, Bitbucket, GitLab, plain Git) and reports one project per
  `Jenkinsfile` found on any of the repository's branches. The project is named after the
  Jenkinsfile's directory.
- **Monorepo project factory** makes each project a multibranch pipeline whose script path is that
  Jenkinsfile.
- **Monorepo build strategy** builds a branch or pull request in a project only when a watched
  path changed.

## What a project watches

Everything under its own directory, plus what `monorepo.yaml` beside its Jenkinsfile adds. Paths
are from the repository root.

```yaml
# services/api/monorepo.yaml
dependsOn: [libs/common]        # that project's directory and everything it watches, transitively
watch: ["shared/schemas/**"]    # plain globs: ** crosses directories, * and ? don't
```

`dependsOn` only decides what rebuilds; it doesn't order builds.

## Statuses

Every project builds the same commits, so each needs its own status, and branch protection needs one
status that exists whichever projects a pull request touches.

| SCM | Each project's status | Overall status on a pull request |
|---|---|---|
| GitHub | `continuous-integration/jenkins/<project>/pr-head` | `continuous-integration/jenkins/monorepo` |
| Bitbucket | already distinct: the key is the job's name | build status with key `jenkins-monorepo` |
| GitLab | `jenkinsci/<project>/mr-head` | `jenkinsci/monorepo` |

The overall status is pending until every project the pull request affects has built its head
commit, then success or failure. A pull request that affects no project succeeds at once.

Each SCM's part is optional and loads only when that SCM's plugin is installed. An installed copy
older than the version this plugin was built against stops this plugin loading; the plugin's page
on plugins.jenkins.io lists those versions.

Another SCM's plugin can add its own support, with an optional dependency on this plugin:
`ProjectSourceCustomizer` makes each project's statuses its own, and `AggregateStatusPublisher` posts
the overall status.

## Job DSL

```groovy
organizationFolder('monorepo') {
  organizations {
    monorepo {
      source { github { /* the repository */ } }
      jenkinsfile('Jenkinsfile')
    }
  }
  projectFactories { monorepoProjects() }
  buildStrategies { monorepoChangedPaths { baseBranch('main') } }
}
```
