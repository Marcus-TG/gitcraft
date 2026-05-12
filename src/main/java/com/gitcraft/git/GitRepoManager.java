package com.gitcraft.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

public final class GitRepoManager {

    private final Path baseGitDir;

    public GitRepoManager(Path baseGitDir) {
        this.baseGitDir = baseGitDir;
    }

    /**
     * Returns the local git working tree root for a GitCraft repo.
     * Layout: {baseGitDir}/{ownerUuid}/{repoName}/
     */
    public Path workingTreePath(UUID ownerUuid, String repoName) {
        return baseGitDir.resolve(ownerUuid.toString()).resolve(repoName);
    }

    /**
     * Opens an existing JGit repository or initialises a new one at the working tree path.
     * The returned Repository must be closed by the caller (or use try-with-resources via Git).
     */
    public Repository openOrInit(UUID ownerUuid, String repoName) throws IOException {
        Path workDir = workingTreePath(ownerUuid, repoName);
        java.nio.file.Files.createDirectories(workDir);

        FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .setWorkTree(workDir.toFile())
                .setGitDir(workDir.resolve(".git").toFile());
        Repository repo = builder.build();

        if (!repo.getObjectDatabase().exists()) {
            repo.create();
        }
        return repo;
    }

    /**
     * Registers (or updates) a named remote URL in the JGit repository config.
     */
    public void setRemoteUrl(Repository repo, String remoteName, String url) throws IOException {
        org.eclipse.jgit.lib.StoredConfig cfg = repo.getConfig();
        cfg.setString("remote", remoteName, "url", url);
        cfg.setString("remote", remoteName, "fetch",
                "+refs/heads/*:refs/remotes/" + remoteName + "/*");
        cfg.save();
    }

    /**
     * Returns a CredentialsProvider that authenticates with GitHub via an OAuth token.
     * GitHub accepts the literal string "token" as the username for HTTPS auth.
     */
    public static UsernamePasswordCredentialsProvider credentials(String accessToken) {
        return new UsernamePasswordCredentialsProvider("token", accessToken);
    }

    /**
     * Checks whether a branch exists locally in the given JGit repository.
     */
    public static boolean branchExists(Repository repo, String branchName) throws IOException {
        return repo.findRef("refs/heads/" + branchName) != null;
    }

    /**
     * Ensures the JGit repo is on the given branch, creating it if necessary.
     * Must be called from an async thread.
     */
    public static void checkoutOrCreateBranch(Git git, String branchName) throws GitAPIException, IOException {
        Repository repo = git.getRepository();
        boolean exists = branchExists(repo, branchName);
        if (!exists && repo.resolve(Constants.HEAD) == null) {
            repo.updateRef(Constants.HEAD).link("refs/heads/" + branchName);
            return;
        }
        git.checkout()
                .setName(branchName)
                .setCreateBranch(!exists)
                .call();
    }
}
