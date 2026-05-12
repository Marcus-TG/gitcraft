package com.gitcraft.util;

public final class Messages {

    private Messages() {}

    public static final String PLAYER_ONLY = "This command can only be run by a player.";
    public static final String NO_PERMISSION = "You don't have permission to use GitCraft.";
    public static final String UNKNOWN_SUBCOMMAND = "Unknown subcommand. Try: init, open, pos1, pos2, clear, commit, log, reset, branch, checkout, diff, merge, cherry-pick, repos, stash, login, logout, remote, push, pull, clone.";

    public static final String INIT_USAGE = "Usage: /gitcraft init <repo-name>";
    public static final String INIT_REPO_EXISTS = "Repo '%s' already exists. Use /gitcraft open %s to reopen it.";
    public static final String INIT_REPO_CREATED = "Repo '%s' initialized on branch 'main'. Select a region with the wand.";
    public static final String INIT_DB_FAILED = "Failed to create repo: %s";

    public static final String SELECT_ENABLED = "Selection mode enabled. Left-click pos1, right-click pos2.";
    public static final String SELECT_WAND_GIVEN = "A selection wand has been added to your inventory.";
    public static final String SELECT_INVALID_NAME = "Invalid name. Allowed: letters, digits, _ and -, up to 64 chars.";

    public static final String POS1_SET = "Pos 1 set: (%d, %d, %d)";
    public static final String POS2_SET = "Pos 2 set: (%d, %d, %d)";
    public static final String CLEARED = "Selection cleared.";

    public static final String NO_SELECTION = "You have no active selection. Use /gitcraft init.";
    public static final String INCOMPLETE_SELECTION = "Selection incomplete. Set both pos1 and pos2.";
    public static final String CROSS_WORLD = "Both corners must be in the same world.";
    public static final String WORLD_NOT_LOADED = "The selection's world is not loaded.";

    public static final String OPEN_USAGE = "Usage: /gitcraft open <repo>";
    public static final String OPEN_REPO_NOT_FOUND = "Repo '%s' not found. Use /gitcraft init %s to create it.";
    public static final String OPEN_NO_COMMITS = "Repo '%s' has no commits on '%s' yet — run /gitcraft commit to make the first one.";
    public static final String OPEN_DB_FAILED = "Failed to look up repo: %s";
    public static final String OPEN_WORLD_GONE = "World '%s' is no longer loaded.";
    public static final String OPEN_RESTORED = "Opened repo '%s' from commit %d. Pos1 (%d, %d, %d) Pos2 (%d, %d, %d)";

    public static final String COMMIT_USAGE = "Usage: /gitcraft commit <msg>";
    public static final String COMMIT_NO_BRANCH = "No active branch. Run /gitcraft init <name> or /gitcraft open <name> first.";
    public static final String COMMIT_EMPTY_MESSAGE = "Commit message cannot be empty.";
    public static final String COMMIT_INVALID_MESSAGE = "Commit message cannot contain newlines.";
    public static final String COMMIT_MESSAGE_TOO_LONG = "Commit message exceeds 500 characters.";
    public static final String COMMIT_STARTED = "Committing selection...";
    public static final String COMMIT_SUCCESS = "Commit %d saved.";
    public static final String COMMIT_DB_FAILED = "Failed to save commit metadata: %s";
    public static final String COMMIT_IO_FAILED = "Failed to write schematic: %s";
    public static final String COMMIT_WE_FAILED = "Schematic build failed: %s";

    public static final String LOG_USAGE = "Usage: /gitcraft log <repo> [branch] [page]";
    public static final String LOG_INVALID_PAGE = "Page must be a positive integer.";
    public static final String LOG_REPO_NOT_FOUND = "Repo '%s' not found.";
    public static final String LOG_BRANCH_NOT_FOUND = "Branch '%s' not found in repo '%s'.";
    public static final String LOG_HEADER = "Commits for '%s' — page %d/%d (%d total):";
    public static final String LOG_FOOTER_NEXT = "More — /gitcraft log %s %s %d";
    public static final String LOG_EMPTY = "No commits found for '%s'.";
    public static final String LOG_PAGE_OUT_OF_RANGE = "Page %d out of range (max %d).";
    public static final String LOG_DB_FAILED = "Failed to read commit history: %s";

    public static final String RESET_USAGE = "Usage: /gitcraft reset <id> [--hard]";
    public static final String RESET_NO_REPO = "No active repo. Use /gitcraft open <name> first.";
    public static final String RESET_WRONG_BRANCH = "Commit %d does not belong to your current branch.";
    public static final String RESET_INVALID_ID = "Invalid commit id. Must be a positive number.";
    public static final String RESET_STARTED = "Resetting to commit...";
    public static final String RESET_NOT_FOUND = "Commit %d not found.";
    public static final String RESET_NOT_OWNER = "You can only reset your own commits.";
    public static final String RESET_FILE_MISSING = "Schematic file missing on disk: %s";
    public static final String RESET_BAD_FORMAT = "Schematic file format not recognized.";
    public static final String RESET_WORLD_GONE = "World '%s' is no longer loaded.";
    public static final String RESET_SUCCESS = "Reset to commit %d (%d blocks).";
    public static final String RESET_DB_FAILED = "Failed to look up commit: %s";
    public static final String RESET_IO_FAILED = "Failed to read schematic: %s";
    public static final String RESET_WE_FAILED = "Reset paste failed: %s";
    public static final String RESET_HARD_WARN = "WARNING: --hard will permanently delete all commits after #%d on that branch. Run the same command again within 30 seconds to confirm.";
    public static final String RESET_HARD_STARTED = "Executing hard reset...";
    public static final String RESET_HARD_SUCCESS = "Hard reset to commit %d (%d blocks pasted, %d commit(s) deleted).";

    public static final String BRANCH_USAGE        = "Usage: /gitcraft branch [<name>]";
    public static final String BRANCH_LIST_HEADER  = "Branches in repo '%s':";
    public static final String BRANCH_LIST_ROW     = "%s %s  \"%s\"";
    public static final String BRANCH_LIST_EMPTY   = "No branches found in this repo.";
    public static final String BRANCH_LIST_NO_COMMITS = "(no commits yet)";
    public static final String BRANCH_LIST_DB_ERROR = "Failed to list branches: %s";
    public static final String BRANCH_NO_REPO      = "No active repo. Use /gitcraft open <name> first.";
    public static final String BRANCH_INVALID_NAME = "Branch name may only contain letters, numbers, hyphens, and underscores (max 64 chars).";
    public static final String BRANCH_NAME_TAKEN   = "A branch named '%s' already exists in this repo.";
    public static final String BRANCH_ALREADY_ON   = "You are already on branch '%s'.";
    public static final String BRANCH_CREATED      = "Created and switched to branch '%s'.";
    public static final String BRANCH_DB_ERROR     = "Branch creation failed: database error. Check server logs.";

    public static final String CHECKOUT_USAGE             = "Usage: /gitcraft checkout <branch>";
    public static final String CHECKOUT_NO_REPO           = "No active repo. Use /gitcraft open <name> first.";
    public static final String CHECKOUT_BRANCH_NOT_FOUND  = "Branch '%s' does not exist in this repo.";
    public static final String CHECKOUT_ALREADY_ON        = "You are already on branch '%s'.";
    public static final String CHECKOUT_EMPTY_BRANCH      = "Switched to branch '%s'. No commits yet — region not restored.";
    public static final String CHECKOUT_WORLD_GONE        = "Switched to branch '%s', but world '%s' is unavailable. Region not restored.";
    public static final String CHECKOUT_FILE_MISSING      = "Checkout failed: schematic file missing for latest commit.";
    public static final String CHECKOUT_FILE_CORRUPT      = "Checkout failed: schematic file is corrupt or unreadable.";
    public static final String CHECKOUT_SUCCESS           = "Switched to branch '%s'. Restored %d blocks.";
    public static final String CHECKOUT_DB_ERROR          = "Checkout failed: database error. Check server logs.";
    public static final String CHECKOUT_IO_ERROR          = "Checkout failed: file I/O error. Check server logs.";
    public static final String CHECKOUT_WE_ERROR          = "Checkout failed: WorldEdit error. Check server logs.";

    public static final String WORLDEDIT_MISSING = "WorldEdit plugin not found. GitCraft requires WorldEdit to be installed.";

    public static final String DIFF_USAGE            = "Usage: /gitcraft diff [<id> | <id1> <id2> | clear]";
    public static final String DIFF_NO_REPO          = "No active repo. Use /gitcraft open <name> first.";
    public static final String DIFF_INVALID_ID       = "Invalid commit id. Must be a positive number.";
    public static final String DIFF_SAME_COMMIT      = "Both commits are the same — nothing to diff.";
    public static final String DIFF_NOT_FOUND        = "Commit %d not found.";
    public static final String DIFF_NO_HEAD          = "No commits on this branch yet.";
    public static final String DIFF_NEED_TWO_COMMITS = "Need at least 2 commits to diff.";
    public static final String DIFF_CROSS_REPO       = "Both commits must belong to the same repo.";
    public static final String DIFF_CROSS_WORLD      = "Commits span different worlds (%s vs %s) — cross-world diff is not supported.";
    public static final String DIFF_NOT_OWNER        = "You can only diff your own repos.";
    public static final String DIFF_DB_FAILED        = "Diff lookup failed: %s";
    public static final String DIFF_IO_FAILED        = "Failed to load schematic: %s";
    public static final String DIFF_NO_CHANGES       = "No differences found between these commits.";
    public static final String DIFF_LARGE_WARN       = "WARNING: This diff has %d changed blocks. Run the same command again within 30 seconds to confirm.";
    public static final String DIFF_SUCCESS          = "Showing diff (%d blocks changed). Run /gitcraft diff clear to dismiss.";
    public static final String DIFF_CLEARED          = "Diff dismissed.";
    public static final String DIFF_WORLD_GONE       = "World '%s' is no longer loaded.";

    public static final String MERGE_USAGE              = "Usage: /gitcraft merge <branch> | accept ours|theirs | continue [msg] | abort | status";
    public static final String MERGE_ACCEPT_USAGE       = "Usage: /gitcraft merge accept ours|theirs";
    public static final String MERGE_NO_REPO            = "No active repo/branch. Use /gitcraft open <name> first.";
    public static final String MERGE_ALREADY_IN_PROGRESS = "A merge is already in progress. Use /gitcraft merge continue or /gitcraft merge abort.";
    public static final String MERGE_NONE               = "No merge in progress.";
    public static final String MERGE_SAME_BRANCH        = "Cannot merge a branch into itself.";
    public static final String MERGE_BRANCH_NOT_FOUND   = "Branch '%s' does not exist in this repo.";
    public static final String MERGE_TARGET_EMPTY       = "Your current branch has no commits — nothing to merge into.";
    public static final String MERGE_SOURCE_EMPTY       = "Source branch has no commits.";
    public static final String MERGE_UNRELATED          = "No common ancestor found between these branches — refusing to merge unrelated histories.";
    public static final String MERGE_CROSS_WORLD        = "Branches span different worlds (%s vs %s). Cross-world merge is not supported.";
    public static final String MERGE_STARTED            = "Computing merge...";
    public static final String MERGE_RESOLVING          = "Merging '%s' into '%s': %d auto-applied, %d conflicts. Use /gitcraft merge accept ours|theirs, then /gitcraft merge continue. Or /gitcraft merge abort.";
    public static final String MERGE_FAST_FORWARD       = "Fast-forward merge of '%s' into '%s' (%d blocks). Finalizing...";
    public static final String MERGE_ALREADY_UP_TO_DATE = "Already up to date.";
    public static final String MERGE_STALE_BASE_WARN    = "HEAD has moved since merge started. Proceeding with original snapshot.";
    public static final String MERGE_ACCEPTED           = "Resolved %d conflicts to '%s'. Run /gitcraft merge continue to commit, or place blocks manually first.";
    public static final String MERGE_UNRESOLVED         = "%d conflicts unresolved. Use /gitcraft merge accept ours|theirs or place blocks then continue.";
    public static final String MERGE_FINALIZING         = "Recording merge commit...";
    public static final String MERGE_ABORTED            = "Merge aborted. World restored to pre-merge state.";
    public static final String MERGE_STATUS             = "Merging '%s' -> '%s': %d auto-applied, %d conflicts (%d unresolved).";
    public static final String MERGE_WORLD_GONE         = "World '%s' is no longer loaded.";
    public static final String MERGE_DB_ERROR           = "Merge failed: database error. Check server logs.";
    public static final String MERGE_DB_FAILED          = "Merge DB lookup failed: %s";
    public static final String MERGE_IO_FAILED          = "Failed to load merge schematic: %s";
    public static final String MERGE_WE_ERROR           = "Merge failed: WorldEdit error. Check server logs.";

    public static final String CHERRYPICK_USAGE              = "Usage: /gitcraft cherry-pick <commit-id> | accept ours|theirs | continue [msg] | abort | status";
    public static final String CHERRYPICK_ACCEPT_USAGE       = "Usage: /gitcraft cherry-pick accept ours|theirs";
    public static final String CHERRYPICK_INVALID_ID         = "Invalid commit id. Must be a positive number.";
    public static final String CHERRYPICK_NO_REPO            = "No active repo/branch. Use /gitcraft open <name> first.";
    public static final String CHERRYPICK_ALREADY_IN_PROGRESS = "An operation (merge or cherry-pick) is already in progress. Use continue or abort first.";
    public static final String CHERRYPICK_NONE               = "No cherry-pick in progress.";
    public static final String CHERRYPICK_COMMIT_NOT_FOUND   = "Commit %d does not exist.";
    public static final String CHERRYPICK_COMMIT_NOT_IN_REPO = "Commit %d does not belong to this repo.";
    public static final String CHERRYPICK_HEAD_EMPTY         = "Your branch has no commits — nothing to cherry-pick onto.";
    public static final String CHERRYPICK_SELF               = "Cannot cherry-pick a commit onto itself.";
    public static final String CHERRYPICK_ALREADY_APPLIED    = "Commit %d is already in this branch's history.";
    public static final String CHERRYPICK_CROSS_WORLD        = "Branches span different worlds (%s vs %s). Cross-world cherry-pick is not supported.";
    public static final String CHERRYPICK_STARTED            = "Computing cherry-pick...";
    public static final String CHERRYPICK_NO_OP              = "Nothing to apply — cherry-pick is a no-op against your current branch.";
    public static final String CHERRYPICK_RESOLVING          = "Cherry-picking commit %d onto '%s': %d auto-applied, %d conflicts. Use /gitcraft cherry-pick accept ours|theirs, then /gitcraft cherry-pick continue. Or /gitcraft cherry-pick abort.";
    public static final String CHERRYPICK_FAST_APPLY         = "Cherry-pick of commit %d onto '%s' (%d blocks). Finalizing...";
    public static final String CHERRYPICK_STALE_BASE_WARN    = "HEAD has moved since cherry-pick started. Proceeding with original snapshot.";
    public static final String CHERRYPICK_ACCEPTED           = "Resolved %d conflicts to '%s'. Run /gitcraft cherry-pick continue to commit, or place blocks manually first.";
    public static final String CHERRYPICK_UNRESOLVED         = "%d conflicts unresolved. Use /gitcraft cherry-pick accept ours|theirs or place blocks then continue.";
    public static final String CHERRYPICK_FINALIZING         = "Recording cherry-pick commit...";
    public static final String CHERRYPICK_ABORTED            = "Cherry-pick aborted. World restored to pre-pick state.";
    public static final String CHERRYPICK_STATUS             = "Cherry-picking commit %d -> '%s': %d auto-applied, %d conflicts (%d unresolved).";
    public static final String CHERRYPICK_WORLD_GONE         = "World '%s' is no longer loaded.";
    public static final String CHERRYPICK_DB_ERROR           = "Cherry-pick failed: database error. Check server logs.";
    public static final String CHERRYPICK_DB_FAILED          = "Cherry-pick DB lookup failed: %s";
    public static final String CHERRYPICK_IO_FAILED          = "Failed to load cherry-pick schematic: %s";
    public static final String CHERRYPICK_WE_ERROR           = "Cherry-pick failed: WorldEdit error. Check server logs.";

    public static final String REPOS_HEADER   = "Your repos (%d):";
    public static final String REPOS_ROW      = "%s  branches: %d  created: %s";
    public static final String REPOS_EMPTY    = "You have no repos. Use /gitcraft init <name> to create one.";
    public static final String REPOS_DB_ERROR = "Failed to list repos: %s";

    public static final String STASH_NO_REPO          = "No active repo. Use /gitcraft open <name> first.";
    public static final String STASH_INVALID_MESSAGE  = "Stash message cannot contain newlines.";
    public static final String STASH_MESSAGE_TOO_LONG = "Stash message exceeds 500 characters.";
    public static final String STASH_PUSHED           = "Stashed selection #%d.";
    public static final String STASH_EMPTY            = "No stashes found for this repo.";
    public static final String STASH_BRANCH_GONE      = "Stash references a branch that no longer exists. Entry removed.";
    public static final String STASH_WORLD_GONE       = "World '%s' is no longer loaded. Stash retained — try again when the world is back.";
    public static final String STASH_POPPED           = "Popped stash #%d on '%s'. Pos1 (%d, %d, %d) Pos2 (%d, %d, %d).";
    public static final String STASH_LIST_HEADER      = "Stashes (%d):";
    public static final String STASH_LIST_ROW         = "#%d  %s  [%d,%d,%d]..[%d,%d,%d]  \"%s\"";
    public static final String STASH_DB_FAILED        = "Stash operation failed: %s";

    // --- GitHub login ---
    public static final String GITHUB_CLIENT_ID_NOT_CONFIGURED = "GitHub client-id is not configured. Ask your server admin to set github.client-id in config.yml.";
    public static final String GITHUB_ALREADY_LOGGED_IN        = "You are already logged in to GitHub. Use /gitcraft logout to switch accounts.";
    public static final String GITHUB_DEVICE_FLOW_PROMPT       = "Visit %s and enter code: %s";
    public static final String GITHUB_LOGIN_SUCCESS            = "GitHub login successful! Scope: %s";
    public static final String GITHUB_LOGIN_EXPIRED            = "Login timed out. Run /gitcraft login again.";
    public static final String GITHUB_LOGIN_NETWORK_FAILED     = "GitHub login failed after repeated network errors. Try again later.";
    public static final String GITHUB_LOGIN_DENIED             = "GitHub login was denied or cancelled.";
    public static final String GITHUB_LOGGED_OUT               = "GitHub token removed.";
    public static final String GITHUB_NOT_LOGGED_IN            = "Not logged in to GitHub. Run /gitcraft login first.";

    // --- Remote management ---
    public static final String REMOTE_USAGE         = "Usage: /gitcraft remote add <name> <url> | list | remove <name>";
    public static final String REMOTE_NO_REPO       = "No active repo. Use /gitcraft open <name> first.";
    public static final String REMOTE_INVALID_NAME  = "Remote name may only contain letters, numbers, hyphens, and underscores (max 32 chars).";
    public static final String REMOTE_INVALID_URL   = "Remote URL must start with https://";
    public static final String REMOTE_ADDED         = "Remote '%s' added: %s";
    public static final String REMOTE_ALREADY_EXISTS = "A remote named '%s' already exists. Remove it first.";
    public static final String REMOTE_NOT_FOUND     = "Remote '%s' not found.";
    public static final String REMOTE_REMOVED       = "Remote '%s' removed.";
    public static final String REMOTE_LIST_HEADER   = "Remotes for '%s':";
    public static final String REMOTE_LIST_ENTRY    = "  %s  %s";
    public static final String REMOTE_LIST_EMPTY    = "No remotes configured. Use /gitcraft remote add origin <url>.";
    public static final String REMOTE_DB_FAILED     = "Remote operation failed: %s";

    // --- Push ---
    public static final String PUSH_USAGE                    = "Usage: /gitcraft push [remote-name]";
    public static final String PUSH_NO_REPO                  = "No active repo/branch. Use /gitcraft open <name> first.";
    public static final String PUSH_NO_TOKEN                 = GITHUB_NOT_LOGGED_IN;
    public static final String PUSH_NO_REMOTE                = "Remote '%s' not found. Add it with /gitcraft remote add %s <url>.";
    public static final String PUSH_NOTHING_TO_PUSH          = "Nothing to push — all commits are already on %s.";
    public static final String PUSH_IN_PROGRESS              = "Pushing %d commit(s) to %s/%s...";
    public static final String PUSH_SUCCESS                  = "Pushed %d commit(s) to %s/%s.";
    public static final String PUSH_REJECTED_NOT_FAST_FORWARD = "Push rejected: remote has new commits. Run /gitcraft pull %s first.";
    public static final String PUSH_REJECTED_NOT_FOUND       = "Push failed: repository not found on GitHub (404). Create the repo on GitHub first.";
    public static final String PUSH_FAILED                   = "Push failed: %s";

    // --- Pull ---
    public static final String PULL_USAGE               = "Usage: /gitcraft pull [remote-name] [--here]";
    public static final String PULL_NO_REPO             = "No active repo/branch. Use /gitcraft open <name> first.";
    public static final String PULL_NO_TOKEN            = GITHUB_NOT_LOGGED_IN;
    public static final String PULL_NO_REMOTE           = PUSH_NO_REMOTE;
    public static final String PULL_NOTHING_TO_PULL     = "Already up to date.";
    public static final String PULL_IN_PROGRESS         = "Fetching from %s...";
    public static final String PULL_SUCCESS             = "Pulled %d commit(s) from %s/%s.";
    public static final String PULL_BLOCKED_ACTIVE_SESSION = "Finish or abort your merge/cherry-pick before pulling.";
    public static final String PULL_FAILED              = "Pull failed: %s";

    // --- Clone ---
    public static final String CLONE_USAGE          = "Usage: /gitcraft clone <https-url> <repo-name> [--here]";
    public static final String CLONE_NO_TOKEN       = GITHUB_NOT_LOGGED_IN;
    public static final String CLONE_INVALID_URL    = REMOTE_INVALID_URL;
    public static final String CLONE_INVALID_NAME   = REMOTE_INVALID_NAME;
    public static final String CLONE_REPO_NAME_TAKEN = "You already have a repo named '%s'. Choose a different name.";
    public static final String CLONE_GIT_DIR_EXISTS  = "Local git directory already exists for '%s'. Delete plugins/GitCraft/git/<uuid>/%s/ manually and try again.";
    public static final String CLONE_IN_PROGRESS    = "Cloning %s...";
    public static final String CLONE_SUCCESS        = "Cloned into repo '%s' (%d commit(s) imported).";
    public static final String CLONE_FAILED         = "Clone failed: %s";
}
