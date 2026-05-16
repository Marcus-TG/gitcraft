package com.gitcraft.git;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GitBranchNames#orderedBranchNames} ref-filtering logic.
 * No Bukkit / Paper dependencies required.
 */
final class GitCloneServiceRefFilterTest {

    // ---- helpers ----

    private static Ref ref(String name) {
        return new ObjectIdRef.PeeledNonTag(Ref.Storage.PACKED, name, ObjectId.zeroId());
    }

    // ---- tests ----

    @Test
    void localRefIsExcluded() {
        List<Ref> refs = List.of(
                ref("refs/heads/main"),          // local — must be excluded
                ref("refs/remotes/origin/main")  // only this should produce "main"
        );
        List<String> names = GitBranchNames.orderedBranchNames(refs,"main");
        assertEquals(List.of("main"), names);
    }

    @Test
    void remoteHeadIsExcluded() {
        List<Ref> refs = List.of(
                ref("refs/remotes/origin/HEAD"),   // symbolic metadata — excluded
                ref("refs/remotes/origin/main"),
                ref("refs/remotes/origin/feature")
        );
        List<String> names = GitBranchNames.orderedBranchNames(refs,"main");
        assertFalse(names.contains("HEAD"), "HEAD must never appear as a branch name");
        assertEquals(List.of("main", "feature"), names);
    }

    @Test
    void allThreeRefTypesFiltered() {
        // The scenario that triggered the duplicate-import bug: local main, remote HEAD,
        // and remote main all present at the same time.
        List<Ref> refs = List.of(
                ref("refs/heads/main"),           // local — excluded
                ref("refs/remotes/origin/HEAD"),  // HEAD — excluded
                ref("refs/remotes/origin/main")   // only valid source for "main"
        );
        List<String> names = GitBranchNames.orderedBranchNames(refs,"main");
        assertEquals(List.of("main"), names);
    }

    @Test
    void duplicateBranchNamesAreDeduped() {
        List<Ref> refs = List.of(
                ref("refs/remotes/origin/main"),
                ref("refs/remotes/origin/main")   // exact duplicate ref
        );
        List<String> names = GitBranchNames.orderedBranchNames(refs,"main");
        assertEquals(List.of("main"), names, "duplicate branch name must appear only once");
    }

    @Test
    void defaultBranchComesFirst() {
        List<Ref> refs = List.of(
                ref("refs/remotes/origin/feature"),
                ref("refs/remotes/origin/main")
        );
        List<String> names = GitBranchNames.orderedBranchNames(refs,"main");
        assertEquals("main", names.get(0), "default branch must be listed first");
        assertEquals(2, names.size());
    }

    @Test
    void defaultBranchHeadNeverAddedAsFirstEntry() {
        // If guessDefaultBranch wrongly returns "HEAD", orderedBranchNames must not add it.
        List<Ref> refs = List.of(
                ref("refs/remotes/origin/HEAD"),
                ref("refs/remotes/origin/main")
        );
        List<String> names = GitBranchNames.orderedBranchNames(refs,"HEAD");
        assertFalse(names.contains("HEAD"), "HEAD must be excluded even when passed as defaultBranchName");
        assertEquals(List.of("main"), names);
    }
}
