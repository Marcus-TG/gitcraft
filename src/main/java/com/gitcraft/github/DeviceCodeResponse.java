package com.gitcraft.github;

public record DeviceCodeResponse(
        String deviceCode,
        String userCode,
        String verificationUri,
        int expiresIn,
        int interval
) {}
