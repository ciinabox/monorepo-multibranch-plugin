package com.base2services.ciinabox.monorepo;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.EnvVars;
import hudson.Util;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitTool;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.util.GitUtils;
import hudson.security.ACL;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import jenkins.model.Jenkins;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.plugins.gitclient.FetchCommand;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

/**
 * One clone of the monorepo's branches on the controller, shared by the folder and every project
 * in it. It answers "where are the Jenkinsfiles" and "what changed between two commits" the same
 * way for every SCM, without a provider API call per project. Only fetching takes the lock and runs
 * git; everything else reads the clone in-process.
 */
final class MonorepoCache {
    private static final String REMOTE_REFS = "refs/remotes/origin/";
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final Map<String, Long> FETCHED = new ConcurrentHashMap<>();
    /** One push reaches every project at once; they share a fetch made within this window. */
    static long fetchWindowMillis = 5000;

    private final GitSCM scm;
    private final String remote;
    private final String credentialsId;
    private final Item context;
    private final File dir;

    private MonorepoCache(GitSCM scm, Item context) throws IOException {
        UserRemoteConfig config = scm.getUserRemoteConfigs().isEmpty() ? null : scm.getUserRemoteConfigs().get(0);
        String url = config == null ? null : config.getUrl();
        if (config == null || url == null) {
            throw new IOException("Monorepo: the source's checkout has no remote");
        }
        this.scm = scm;
        this.remote = url;
        this.credentialsId = config.getCredentialsId();
        this.context = context;
        this.dir = new File(Jenkins.get().getRootDir(), "caches/monorepo-" + Util.getDigestOf(remote));
    }

    /**
     * The clone for a source. What to fetch and how to authenticate come from the checkout the source
     * would do for a branch: that is the one thing every Git-based branch source has in common (not
     * all of them extend the git plugin's source), and its extensions add what the git plugin can't
     * (an OAuth token, for example).
     *
     * @param context where to look the source's credentials up: the folder, or a project in it.
     */
    static MonorepoCache of(SCMSource source, @CheckForNull Item context) throws IOException {
        // The head only has to be a branch; the refspec fetched is ours
        if (!(source.build(new SCMHead("main")) instanceof GitSCM scm)) {
            throw new IOException("Monorepo: " + source.getDescriptor().getDisplayName() + " doesn't check out with Git");
        }
        return new MonorepoCache(scm, context);
    }

    String remote() {
        return remote;
    }

    private interface Work<T> {
        T run(Repository repo) throws IOException;
    }

    /**
     * Reads the clone in-process: no lock and no git process, so every project deciding on one push
     * doesn't queue behind the others. Reading is safe while a fetch runs.
     */
    private <T> T read(Work<T> work) throws IOException {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(new File(dir, ".git")).setMustExist(true).build()) {
            return work.run(repo);
        }
    }

    private boolean has(String... commits) throws IOException {
        if (!new File(dir, ".git").isDirectory()) {
            return false;
        }
        return read(repo -> {
            for (String commit : commits) {
                if (resolve(repo, commit) == null) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * This clone is on the controller, and nothing in it needs a hook: point git's hooks at nothing,
     * so a hook can't run there whatever the global git configuration says. (The git plugin does the
     * same for its controller clones unless an administrator allows hooks.)
     */
    private void disableHooks() throws IOException {
        read(repo -> {
            StoredConfig config = repo.getConfig();
            String none = File.pathSeparatorChar == ';' ? "NUL:" : "/dev/null";
            if (!none.equals(config.getString("core", null, "hooksPath"))) {
                config.setString("core", null, "hooksPath", none);
                config.save();
            }
            return null;
        });
    }

    /** Fetches unless the clone already has these commits; a commit still missing afterwards isn't on a branch. */
    private void need(TaskListener listener, String... commits) throws IOException, InterruptedException {
        if (!has(commits)) {
            fetch(listener);
        }
    }

    /** Fetches the repository's branches, unless that was done within the window. */
    private void fetch(TaskListener listener) throws IOException, InterruptedException {
        ReentrantLock lock = LOCKS.computeIfAbsent(remote, r -> new ReentrantLock());
        lock.lock();
        try {
            Long last = FETCHED.get(remote);
            if (last != null && System.currentTimeMillis() - last <= fetchWindowMillis
                    && new File(dir, ".git").isDirectory()) {
                return;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("can't create " + dir);
            }
            Git git = Git.with(listener, new EnvVars(EnvVars.masterEnvVars)).in(dir);
            GitTool tool = GitUtils.resolveGitTool(null, Jenkins.get(), null, TaskListener.NULL);
            if (tool != null) {
                git.using(tool.getGitExe());
            }
            GitClient client = git.getClient();
            if (!client.hasGitRepo(false)) {
                client.init();
            }
            disableHooks();
            String url = remote;
            StandardUsernameCredentials credentials = credentials(credentialsId, url);
            if (credentials != null) {
                client.addDefaultCredentials(credentials);
            }
            for (GitSCMExtension extension : scm.getExtensions()) {
                client = extension.decorate(scm, client);
            }
            try {
                FetchCommand fetch = client.fetch_()
                        .from(new URIish(url), List.of(new RefSpec("+refs/heads/*:" + REMOTE_REFS + "*")))
                        .prune(true)
                        .tags(false);
                for (GitSCMExtension extension : scm.getExtensions()) {
                    extension.decorateFetchCommand(scm, (Run<?, ?>) null, client, listener, fetch);
                }
                fetch.execute();
            } catch (java.net.URISyntaxException e) {
                throw new IOException(e);
            }
            FETCHED.put(remote, System.currentTimeMillis());
        } finally {
            lock.unlock();
        }
    }

    @CheckForNull
    private StandardUsernameCredentials credentials(@CheckForNull String credentialsId, String url) {
        if (credentialsId == null) {
            return null;
        }
        StandardUsernameCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItem(
                        StandardUsernameCredentials.class, context, ACL.SYSTEM2,
                        URIRequirementBuilder.fromUri(url).build()),
                CredentialsMatchers.withId(credentialsId));
        return credentials != null && GitClient.CREDENTIALS_MATCHER.matches(credentials) ? credentials : null;
    }

    /**
     * The project names for every file called {@code jenkinsfile} on any branch of the repository.
     * Only the repository's own branches are fetched, so a pull request from a fork can't create one.
     */
    Set<String> projects(String jenkinsfile, TaskListener listener) throws IOException, InterruptedException {
        fetch(listener);
        return read(repo -> {
            Set<String> names = new TreeSet<>();
            Set<ObjectId> walked = new java.util.HashSet<>();
            try (RevWalk revWalk = new RevWalk(repo)) {
                for (Ref ref : repo.getRefDatabase().getRefsByPrefix(REMOTE_REFS)) {
                    RevCommit commit = revWalk.parseCommit(ref.getObjectId());
                    if (!walked.add(commit.getTree().getId())) {
                        continue;
                    }
                    try (TreeWalk walk = new TreeWalk(repo)) {
                        walk.addTree(commit.getTree());
                        walk.setRecursive(true);
                        while (walk.next()) {
                            if (walk.getNameString().equals(jenkinsfile)) {
                                names.add(Projects.nameFor(walk.getPathString()));
                            }
                        }
                    }
                }
            }
            return names;
        });
    }

    /** The project names for the files called {@code jenkinsfile} at one commit, or null when it isn't in the cache. */
    @CheckForNull
    Set<String> projectsAt(String commit, String jenkinsfile, TaskListener listener)
            throws IOException, InterruptedException {
        need(listener, commit);
        return read(repo -> {
            try (RevWalk revWalk = new RevWalk(repo); TreeWalk walk = new TreeWalk(repo)) {
                RevCommit c = commit(repo, revWalk, commit);
                if (c == null) {
                    return null;
                }
                Set<String> names = new TreeSet<>();
                walk.addTree(c.getTree());
                walk.setRecursive(true);
                while (walk.next()) {
                    if (walk.getNameString().equals(jenkinsfile)) {
                        names.add(Projects.nameFor(walk.getPathString()));
                    }
                }
                return names;
            }
        });
    }

    /** The commit a branch of the repository points at, or null. */
    @CheckForNull
    String branch(String name, TaskListener listener) throws IOException, InterruptedException {
        fetch(listener);
        return read(repo -> {
            Ref ref = repo.exactRef(REMOTE_REFS + name);
            return ref == null ? null : ref.getObjectId().name();
        });
    }

    /** The common ancestor of two commits, or null when either isn't in the cache or they share none. */
    @CheckForNull
    String mergeBase(String a, String b, TaskListener listener) throws IOException, InterruptedException {
        need(listener, a, b);
        return read(repo -> {
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit ca = commit(repo, walk, a);
                RevCommit cb = commit(repo, walk, b);
                if (ca == null || cb == null) {
                    return null;
                }
                walk.setRevFilter(RevFilter.MERGE_BASE);
                walk.markStart(ca);
                walk.markStart(cb);
                RevCommit base = walk.next();
                return base == null ? null : base.name();
            }
        });
    }

    /** Every path that differs between two commits, or null when either isn't in the cache. */
    @CheckForNull
    Set<String> changedPaths(String from, String to, TaskListener listener) throws IOException, InterruptedException {
        need(listener, from, to);
        return read(repo -> {
            try (RevWalk walk = new RevWalk(repo);
                    DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                RevCommit cf = commit(repo, walk, from);
                RevCommit ct = commit(repo, walk, to);
                if (cf == null || ct == null) {
                    return null;
                }
                diff.setRepository(repo);
                Set<String> paths = new TreeSet<>();
                for (DiffEntry entry : diff.scan(cf.getTree(), ct.getTree())) {
                    if (entry.getChangeType() != DiffEntry.ChangeType.ADD) {
                        paths.add(entry.getOldPath());
                    }
                    if (entry.getChangeType() != DiffEntry.ChangeType.DELETE) {
                        paths.add(entry.getNewPath());
                    }
                }
                return paths;
            }
        });
    }

    /** A file's text at a commit, or null when the file or the commit isn't there. */
    @CheckForNull
    String read(String commit, String path, TaskListener listener) throws IOException, InterruptedException {
        if (!has(commit)) {
            return null;
        }
        return read(repo -> {
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit c = commit(repo, walk, commit);
                if (c == null) {
                    return null;
                }
                try (TreeWalk tree = TreeWalk.forPath(repo, path, c.getTree())) {
                    if (tree == null) {
                        return null;
                    }
                    return new String(repo.open(tree.getObjectId(0), Constants.OBJ_BLOB).getBytes(),
                            StandardCharsets.UTF_8);
                }
            }
        });
    }

    @CheckForNull
    private static RevCommit commit(Repository repo, RevWalk walk, String hash) throws IOException {
        ObjectId id = resolve(repo, hash);
        return id == null ? null : walk.parseCommit(id);
    }

    /**
     * A commit in the clone by its hash, which may be shortened: Bitbucket Cloud reports a pull
     * request's commits by their first twelve characters. Null when it isn't there or is ambiguous.
     */
    @CheckForNull
    private static ObjectId resolve(Repository repo, @CheckForNull String hash) throws IOException {
        if (hash == null || !hash.matches("[0-9a-fA-F]{7,40}")) {
            return null;
        }
        if (hash.length() == 40) {
            ObjectId id = ObjectId.fromString(hash);
            return repo.getObjectDatabase().has(id) ? id : null;
        }
        try (ObjectReader reader = repo.newObjectReader()) {
            Collection<ObjectId> matches = reader.resolve(AbbreviatedObjectId.fromString(hash));
            return matches.size() == 1 ? matches.iterator().next() : null;
        }
    }

    /** The full hash of a commit that may be shortened, or null when the clone doesn't have it. */
    @CheckForNull
    String full(@CheckForNull String commit, TaskListener listener) throws IOException, InterruptedException {
        if (commit == null) {
            return null;
        }
        need(listener, commit);
        if (!new File(dir, ".git").isDirectory()) {
            return null;
        }
        return read(repo -> {
            ObjectId id = resolve(repo, commit);
            return id == null ? null : id.name();
        });
    }
}
