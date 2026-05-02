package com.gitcraft.diff;

import java.util.List;

public record DiffResult(List<GhostBlock> ghosts) {

    public int totalCount() {
        return ghosts.size();
    }
}
