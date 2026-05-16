package com.gitcraft.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GitPullPlannerTest {

    @TempDir
    Path tempDir;

    @Test
    void branchPullSkipsKnownSharedHistoryFromOtherBranches() throws Exception {
        Path repoDir = tempDir.resolve("repo");
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            GitRepoManager.checkoutOrCreateBranch(git, "main");
            String baseSha = commit(git, "base.txt", "base", "base");
            String mainSha = commit(git, "main.txt", "main", "main");

            GitRepoManager.checkoutOrCreateBranch(git, "feature", baseSha);
            String featureSha = commit(git, "feature.txt", "feature", "feature");

            List<RevCommit> planned = GitPullPlanner.collectNewCommitsOldestFirst(
                    git.getRepository(),
                    ObjectId.fromString(featureSha),
                    Map.of(baseSha, 1L, mainSha, 2L));

            assertEquals(List.of(featureSha), planned.stream().map(RevCommit::name).toList());
        }
    }

    @Test
    void newLocalBranchStartsFromExplicitParentSha() throws Exception {
        Path repoDir = tempDir.resolve("repo");
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            GitRepoManager.checkoutOrCreateBranch(git, "main");
            String forkSha = commit(git, "fork.txt", "fork", "fork");
            commit(git, "main.txt", "main", "main");

            GitRepoManager.checkoutOrCreateBranch(git, "feature", forkSha);
            String featureSha = commit(git, "feature.txt", "feature", "feature");

            try (RevWalk rw = new RevWalk(git.getRepository())) {
                RevCommit feature = rw.parseCommit(ObjectId.fromString(featureSha));
                assertEquals(forkSha, feature.getParent(0).name());
            }
        }
    }

    private String commit(Git git, String fileName, String content, String message) throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve(fileName), content);
        git.add().addFilepattern(fileName).call();
        return git.commit().setMessage(message).call().name();
    }
}
