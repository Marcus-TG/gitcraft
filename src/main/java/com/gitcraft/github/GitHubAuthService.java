package com.gitcraft.github;

import com.gitcraft.database.GitHubTokenDao;
import com.gitcraft.database.GitHubTokenRecord;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class GitHubAuthService {

    private static final String DEVICE_CODE_URL     = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL    = "https://github.com/login/oauth/access_token";
    private static final String SCOPE               = "repo";
    private static final int    MAX_NETWORK_RETRIES = 3;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * Step 1: Request device code from GitHub.
     */
    public DeviceCodeResponse requestDeviceCode(String clientId) throws IOException {
        String body = "client_id=" + encode(clientId) + "&scope=" + encode(SCOPE);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DEVICE_CODE_URL))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        Map<String, String> resp = sendJson(req);
        String deviceCode   = require(resp, "device_code");
        String userCode     = require(resp, "user_code");
        String verifyUri    = require(resp, "verification_uri");
        int expiresIn       = Integer.parseInt(resp.getOrDefault("expires_in", "900"));
        int interval        = Integer.parseInt(resp.getOrDefault("interval", "5"));
        return new DeviceCodeResponse(deviceCode, userCode, verifyUri, expiresIn, interval);
    }

    /**
     * Step 2 (single poll): returns the access token on success, null if still pending.
     * Throws IOException on hard errors (expired, denied, network).
     */
    public String pollForToken(String clientId, String deviceCode) throws IOException {
        String body = "client_id=" + encode(clientId)
                + "&device_code=" + encode(deviceCode)
                + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ACCESS_TOKEN_URL))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        Map<String, String> resp = sendJson(req);
        if (resp.containsKey("access_token")) return resp.get("access_token");

        String error = resp.getOrDefault("error", "");
        return switch (error) {
            case "authorization_pending" -> null;
            case "slow_down" -> throw new IOException("slow_down");
            case "expired_token"  -> throw new IOException("expired_token");
            case "access_denied"  -> throw new IOException("access_denied");
            default               -> throw new IOException("GitHub error: " + error);
        };
    }

    /**
     * Starts the full async device flow. Sends the player the URL + code, then polls
     * on the async scheduler until authorized, expired, or denied.
     */
    public void startAuthFlow(Player player, String clientId,
                              GitHubTokenDao tokenDao, Plugin plugin) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DeviceCodeResponse dcr;
            try {
                dcr = requestDeviceCode(clientId);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "GitHub device code request failed", e);
                sendMessage(plugin, playerId, String.format(Messages.GITHUB_LOGIN_NETWORK_FAILED));
                return;
            }

            sendMessage(plugin, playerId,
                    String.format(Messages.GITHUB_DEVICE_FLOW_PROMPT, dcr.verificationUri(), dcr.userCode()));

            // intervalTicks[0] is mutable in the lambda via array trick
            int[] intervalSeconds = {dcr.interval()};
            AtomicInteger networkFailures = new AtomicInteger(0);

            scheduleNextPoll(plugin, playerId, clientId, dcr.deviceCode(), intervalSeconds,
                    networkFailures, tokenDao);
        });
    }

    private void scheduleNextPoll(Plugin plugin, UUID playerId, String clientId,
                                  String deviceCode, int[] intervalSeconds,
                                  AtomicInteger networkFailures, GitHubTokenDao tokenDao) {
        long delayTicks = (long) intervalSeconds[0] * 20L;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            String token;
            try {
                token = pollForToken(clientId, deviceCode);
                networkFailures.set(0);
            } catch (IOException e) {
                String msg = e.getMessage();
                if ("expired_token".equals(msg)) {
                    sendMessage(plugin, playerId, Messages.GITHUB_LOGIN_EXPIRED);
                    return;
                }
                if ("access_denied".equals(msg)) {
                    sendMessage(plugin, playerId, Messages.GITHUB_LOGIN_DENIED);
                    return;
                }
                if ("slow_down".equals(msg)) {
                    intervalSeconds[0] += 5;
                    scheduleNextPoll(plugin, playerId, clientId, deviceCode, intervalSeconds,
                            networkFailures, tokenDao);
                    return;
                }
                // Transient network error
                if (networkFailures.incrementAndGet() >= MAX_NETWORK_RETRIES) {
                    sendMessage(plugin, playerId, Messages.GITHUB_LOGIN_NETWORK_FAILED);
                    return;
                }
                // Retry silently
                scheduleNextPoll(plugin, playerId, clientId, deviceCode, intervalSeconds,
                        networkFailures, tokenDao);
                return;
            }

            if (token == null) {
                // authorization_pending — reschedule at same interval
                scheduleNextPoll(plugin, playerId, clientId, deviceCode, intervalSeconds,
                        networkFailures, tokenDao);
                return;
            }

            // Fetch scope from token info — we already know it's "repo" but store what GitHub returned
            try {
                tokenDao.upsert(new GitHubTokenRecord(playerId, token, SCOPE, System.currentTimeMillis()));
                sendMessage(plugin, playerId, String.format(Messages.GITHUB_LOGIN_SUCCESS, SCOPE));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to store GitHub token", e);
                sendMessage(plugin, playerId, "GitHub login succeeded but token could not be saved: " + e.getMessage());
            }
        }, delayTicks);
    }

    // ---- helpers ----

    private Map<String, String> sendJson(HttpRequest req) throws IOException {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return parseFormOrJson(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }
    }

    /** Parses both application/json and application/x-www-form-urlencoded responses. */
    private Map<String, String> parseFormOrJson(String body) {
        Map<String, String> map = new HashMap<>();
        body = body.strip();
        if (body.startsWith("{")) {
            // Minimal JSON parse for flat string/number values — no external dep needed
            body = body.replaceAll("[{}\"]", "");
            for (String pair : body.split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) map.put(kv[0].strip(), kv[1].strip());
            }
        } else {
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) map.put(decode(kv[0]), decode(kv[1]));
            }
        }
        return map;
    }

    private static String require(Map<String, String> map, String key) throws IOException {
        String v = map.get(key);
        if (v == null || v.isBlank()) throw new IOException("Missing field '" + key + "' in GitHub response");
        return v;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static void sendMessage(Plugin plugin, UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }
}
