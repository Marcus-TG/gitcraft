package com.gitcraft.git;

import org.eclipse.jgit.lib.Ref;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-logic helpers for working with remote-tracking branch refs during clone/pull.
 * No Paper/Bukkit imports — this class is safe to reference from unit tests.
 */
final class GitBranchNames {

    private GitBranchNames() {}

    /**
     * Builds the ordered list of branch names to import from a set of JGit remote refs.
     * <p>
     * Rules:
     * <ul>
     *   <li>Only refs under {@code refs/remotes/origin/} are considered — local
     *       {@code refs/heads/*} and other namespaces are excluded.</li>
     *   <li>{@code refs/remotes/origin/HEAD} is excluded (symbolic metadata, not a real branch).</li>
     *   <li>Duplicate branch names are silently deduplicated (insertion-order preserved).</li>
     *   <li>The default branch is placed first when it has a real remote-tracking ref and is not
     *       {@code "HEAD"}.</li>
     * </ul>
     */
    static List<String> orderedBranchNames(List<Ref> remoteBranches, String defaultBranchName) {
        String prefix = "refs/remotes/origin/";
        String headFull = prefix + "HEAD";
        List<String> names = new ArrayList<>();
        // Default branch first — only if a real remote-tracking ref exists and the name is valid.
        if (!defaultBranchName.equals("HEAD") &&
                remoteBranches.stream().anyMatch(r -> r.getName().equals(prefix + defaultBranchName))) {
            names.add(defaultBranchName);
        }
        for (Ref r : remoteBranches) {
            String fullName = r.getName();
            if (!fullName.startsWith(prefix)) continue;  // skip refs/heads/* and other namespaces
            if (fullName.equals(headFull)) continue;      // skip refs/remotes/origin/HEAD
            String branchName = fullName.substring(prefix.length());
            if (!names.contains(branchName)) names.add(branchName);  // deduplicate
        }
        return names;
    }
}
