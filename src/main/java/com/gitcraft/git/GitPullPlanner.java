package com.gitcraft.git;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure JGit planning for pull imports. Keeping this separate from Bukkit-facing pull code
 * makes the branch DAG edge cases testable without a Paper server.
 */
final class GitPullPlanner {

    private GitPullPlanner() {}

    static List<RevCommit> collectNewCommitsOldestFirst(Repository repo, ObjectId remoteTip,
                                                        Map<String, Long> knownShas)
            throws IOException {
        List<RevCommit> newCommits = new ArrayList<>();
        try (RevWalk rw = new RevWalk(repo)) {
            rw.markStart(rw.parseCommit(remoteTip));
            for (String knownSha : knownShas.keySet()) {
                ObjectId known = repo.resolve(knownSha);
                if (known != null) {
                    rw.markUninteresting(rw.parseCommit(known));
                }
            }
            for (RevCommit rc : rw) {
                if (!knownShas.containsKey(rc.name())) {
                    newCommits.add(rc);
                }
            }
        }
        return newCommits.reversed();
    }
}
