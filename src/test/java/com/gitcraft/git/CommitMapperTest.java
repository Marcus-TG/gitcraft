package com.gitcraft.git;

import com.gitcraft.database.CommitRecord;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class CommitMapperTest {

    @TempDir
    Path tempDir;

    @Test
    void mergeCommitContainsStagedSnapshotAndExplicitSchematicPath() throws Exception {
        CommitMapper mapper = new CommitMapper();
        Path repoDir = tempDir.resolve("repo");

        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            GitRepoManager.checkoutOrCreateBranch(git, "main");

            String firstSha = mapper.createGitCommit(git, record(1L, null, null, "first"),
                    "main", source("first".getBytes()), null);

            GitRepoManager.checkoutOrCreateBranch(git, "feature");
            String featureSha = mapper.createGitCommit(git, record(2L, 1L, null, "feature"),
                    "feature", source("feature".getBytes()), null);

            git.checkout().setName("main").call();
            mapper.createGitCommit(git, record(3L, 1L, null, "main second"),
                    "main", source("main-second".getBytes()), null);

            String mergeSha = mapper.createGitCommit(git, record(4L, 3L, 2L, "merge"),
                    "main", source("merge-snapshot".getBytes()), featureSha);

            Repository repo = git.getRepository();
            try (RevWalk rw = new RevWalk(repo)) {
                RevCommit mergeCommit = rw.parseCommit(ObjectId.fromString(mergeSha));
                assertEquals(2, mergeCommit.getParentCount());

                Map<String, String> metadata = mapper.readMetadataFromCommit(repo, mergeCommit, rw);
                assertEquals("main/4.schem", metadata.get(CommitMapper.SCHEM_PATH_KEY));
                assertEquals("merge", metadata.get("message"));

                Path extracted = tempDir.resolve("extracted.schem");
                mapper.extractSchematic(repo, mergeCommit, rw, metadata.get(CommitMapper.SCHEM_PATH_KEY), extracted);
                assertArrayEquals("merge-snapshot".getBytes(), Files.readAllBytes(extracted));
            }

            try (RevWalk rw = new RevWalk(git.getRepository())) {
                RevCommit firstCommit = rw.parseCommit(ObjectId.fromString(firstSha));
                Map<String, String> metadata = mapper.readMetadataFromCommit(git.getRepository(), firstCommit, rw);
                assertEquals("main/1.schem", metadata.get(CommitMapper.SCHEM_PATH_KEY));
            }
        }
    }

    private Path source(byte[] bytes) throws Exception {
        Path source = Files.createTempFile(tempDir, "source-", ".schem");
        Files.write(source, bytes);
        return source;
    }

    private static CommitRecord record(Long id, Long parentId, Long mergeParentId, String message) {
        return new CommitRecord(
                id,
                parentId,
                mergeParentId,
                null,
                10L,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "Steve",
                message,
                "/unused/" + id + ".schem",
                1_700_000_000_000L + id,
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                "world",
                1,
                2,
                3,
                4,
                5,
                6
        );
    }
}
