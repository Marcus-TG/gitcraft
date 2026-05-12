package com.gitcraft.git;

import com.gitcraft.database.CommitRecord;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Converts between GitCraft CommitRecords and real Git commits stored in a JGit repository.
 *
 * Git working tree layout per repo:
 *   {branchName}/{commitId}.schem   — schematic snapshot (commit_id is the original server's id)
 *   gitcraft.json                   — metadata for the most recent commit
 *
 * NOTE: the commit_id embedded in filenames and gitcraft.json is the *original server's*
 * local DB integer. On a different server the auto-increment will assign a different local ID.
 * Parent relationships are reconstructed from RevCommit.getParents(), not from the JSON integers.
 */
public final class CommitMapper {

    public static final String SCHEM_PATH_KEY = "schem_path";

    /**
     * Creates a real Git commit from a GitCraft CommitRecord.
     *
     * Writes the .schem file and gitcraft.json into the JGit working tree, stages them, and
     * creates a Git commit. For merge commits (mergeParentSha != null), creates a two-parent commit.
     *
     * Returns the new Git commit SHA (40-char hex).
     * Must be called from an async thread.
     */
    public String createGitCommit(Git git, CommitRecord record, String branchName,
                                  Path schemSourcePath, String mergeParentSha) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        String schemFileName = schematicPath(branchName, record.id());

        // Stage schematic file
        Path schemDest = repo.getWorkTree().toPath().resolve(schemFileName);
        Files.createDirectories(schemDest.getParent());
        Files.copy(schemSourcePath, schemDest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Stage gitcraft.json
        Path jsonDest = repo.getWorkTree().toPath().resolve("gitcraft.json");
        Files.writeString(jsonDest, buildJson(record, branchName), StandardCharsets.UTF_8);

        git.add().addFilepattern(schemFileName).addFilepattern("gitcraft.json").call();

        PersonIdent author = new PersonIdent(
                record.playerName(),
                record.playerUuid() + "@gitcraft.local",
                new Date(record.createdAt()),
                TimeZone.getTimeZone("UTC"));

        if (mergeParentSha != null) {
            // Two-parent merge commit — JGit's CommitCommand doesn't support two parents directly;
            // use low-level ObjectInserter instead.
            return createMergeCommit(repo, author, record.message(), mergeParentSha);
        }

        org.eclipse.jgit.api.CommitCommand commit = git.commit()
                .setAuthor(author)
                .setCommitter(author)
                .setMessage(record.message() != null ? record.message() : "");
        RevCommit rc = commit.call();
        return rc.name();
    }

    /**
     * Creates a two-parent merge commit using the JGit low-level API.
     * The current HEAD is parent[0]; mergeParentSha is parent[1].
     */
    private String createMergeCommit(Repository repo, PersonIdent author,
                                     String message, String mergeParentSha) throws IOException {
        ObjectId headId = repo.resolve(Constants.HEAD);
        ObjectId mergeId = ObjectId.fromString(mergeParentSha);

        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit headCommit  = rw.parseCommit(headId);
            RevCommit mergeCommit = rw.parseCommit(mergeId);

            try (ObjectInserter inserter = repo.newObjectInserter()) {
                DirCache index = repo.readDirCache();
                ObjectId treeId = index.writeTree(inserter);

                CommitBuilder cb = new CommitBuilder();
                cb.setAuthor(author);
                cb.setCommitter(author);
                cb.setMessage(message != null ? message : "");
                cb.setTreeId(treeId);
                cb.setParentIds(headCommit, mergeCommit);
                ObjectId commitId = inserter.insert(cb);
                inserter.flush();

                // Update the branch ref
                RefUpdate ru = repo.updateRef(Constants.HEAD);
                ru.setNewObjectId(commitId);
                ru.setRefLogMessage("commit (merge)", false);
                ru.update(rw);

                return commitId.name();
            }
        }
    }

    /**
     * Reads and parses gitcraft.json from a specific Git commit.
     * Returns the metadata as a raw string-to-string map.
     */
    public Map<String, String> readMetadata(Repository repo, String gitSha) throws IOException {
        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit commit = rw.parseCommit(ObjectId.fromString(gitSha));
            return readMetadataFromCommit(repo, commit, rw);
        }
    }

    public Map<String, String> readMetadataFromCommit(Repository repo, RevCommit commit, RevWalk rw)
            throws IOException {
        rw.parseBody(commit);
        try (TreeWalk tw = TreeWalk.forPath(repo, "gitcraft.json", commit.getTree())) {
            if (tw == null) return new HashMap<>();
            ObjectId blobId = tw.getObjectId(0);
            byte[] bytes = repo.open(blobId).getBytes();
            return parseJson(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    /**
     * Extracts the .schem file from a specific Git commit and writes it to targetPath.
     */
    public void extractSchematic(Repository repo, RevCommit commit, RevWalk rw,
                                 String schemPathInTree, Path targetPath) throws IOException {
        rw.parseBody(commit);
        try (TreeWalk tw = TreeWalk.forPath(repo, schemPathInTree, commit.getTree())) {
            if (tw == null) throw new IOException("Schematic not found in commit: " + schemPathInTree);
            ObjectId blobId = tw.getObjectId(0);
            Files.createDirectories(targetPath.getParent());
            try (InputStream in = repo.open(blobId).openStream()) {
                Files.copy(in, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ---- helpers ----

    private String buildJson(CommitRecord r, String branchName) {
        return "{\n" +
               "  \"version\": 1,\n" +
               "  \"commit_id\": " + r.id() + ",\n" +
               "  \"player_uuid\": \"" + r.playerUuid() + "\",\n" +
               "  \"player_name\": \"" + escape(r.playerName()) + "\",\n" +
               "  \"message\": \"" + escape(r.message() != null ? r.message() : "") + "\",\n" +
               "  \"branch\": \"" + escape(branchName) + "\",\n" +
               "  \"schem_path\": \"" + escape(schematicPath(branchName, r.id())) + "\",\n" +
               "  \"world_uuid\": \"" + r.worldUuid() + "\",\n" +
               "  \"world_name\": \"" + escape(r.worldName()) + "\",\n" +
               "  \"min_x\": " + r.minX() + ", \"min_y\": " + r.minY() + ", \"min_z\": " + r.minZ() + ",\n" +
               "  \"max_x\": " + r.maxX() + ", \"max_y\": " + r.maxY() + ", \"max_z\": " + r.maxZ() + ",\n" +
               "  \"created_at\": " + r.createdAt() + ",\n" +
               "  \"parent_commit_id\": " + (r.parentCommitId() != null ? r.parentCommitId() : "null") + ",\n" +
               "  \"merge_parent_commit_id\": " + (r.mergeParentCommitId() != null ? r.mergeParentCommitId() : "null") + ",\n" +
               "  \"cherry_pick_source_id\": " + (r.cherryPickSourceId() != null ? r.cherryPickSourceId() : "null") + "\n" +
               "}";
    }

    /** Minimal flat JSON parser — sufficient for the fixed gitcraft.json schema. */
    private Map<String, String> parseJson(String json) {
        Map<String, String> map = new HashMap<>();
        // Strip outer braces and newlines
        String body = json.replaceAll("[{}\\n\\r]", "").strip();
        for (String pair : body.split(",")) {
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String key = pair.substring(0, colon).replaceAll("\"", "").strip();
            String val = pair.substring(colon + 1).replaceAll("\"", "").strip();
            map.put(key, "null".equals(val) ? null : val);
        }
        return map;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String schematicPath(String branchName, long commitId) {
        return branchName + "/" + commitId + ".schem";
    }
}
