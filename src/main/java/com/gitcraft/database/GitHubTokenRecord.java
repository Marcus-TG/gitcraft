package com.gitcraft.database;

import java.util.UUID;

public record GitHubTokenRecord(UUID playerUuid, String accessToken, String scope, long createdAt) {}
