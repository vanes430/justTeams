package eu.kotori.justTeams.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.kotori.justTeams.JustTeams;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DiscordWebhookManager {
    
    private final JustTeams plugin;
    private FileConfiguration webhookConfig;
    private File webhookFile;
    
    private boolean enabled;
    private String webhookUrl;
    private String username;
    private String avatarUrl;
    private String serverName;
    
    private boolean rateLimitingEnabled;
    private int maxPerMinute;
    private long minDelayMs;
    private final AtomicInteger requestsThisMinute = new AtomicInteger(0);
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final AtomicLong lastMinuteReset = new AtomicLong(System.currentTimeMillis());
    
    private boolean retryEnabled;
    private int maxRetryAttempts;
    private long retryDelayMs;
    
    private boolean showServerIp;
    private boolean showUuids;
    private int connectionTimeout;
    private int readTimeout;
    private boolean logErrors;
    private boolean logSuccess;
    private boolean asyncSending;
    private boolean queueOnRateLimit;
    private int maxQueueSize;
    
    private final BlockingQueue<WebhookMessage> messageQueue;
    private final ScheduledExecutorService executor;
    private final ExecutorService asyncExecutor;
    
    private static class WebhookMessage {
        final String eventType;
        final Map<String, String> placeholders;
        final int retryCount;
        final long scheduledTime;
        
        WebhookMessage(String eventType, Map<String, String> placeholders) {
            this(eventType, placeholders, 0, System.currentTimeMillis());
        }
        
        WebhookMessage(String eventType, Map<String, String> placeholders, int retryCount, long scheduledTime) {
            this.eventType = eventType;
            this.placeholders = placeholders;
            this.retryCount = retryCount;
            this.scheduledTime = scheduledTime;
        }
        
        WebhookMessage withRetry() {
            return new WebhookMessage(eventType, placeholders, retryCount + 1, System.currentTimeMillis());
        }
    }
    
    public DiscordWebhookManager(JustTeams plugin) {
        this.plugin = plugin;
        this.messageQueue = new LinkedBlockingQueue<>();
        this.executor = Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r, "JustTeams-Webhook-Processor");
            thread.setDaemon(true);
            return thread;
        });
        this.asyncExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "JustTeams-Webhook-Sender");
            thread.setDaemon(true);
            return thread;
        });
        
        loadConfiguration();
        startQueueProcessor();
    }
    
    private void loadConfiguration() {
        webhookFile = new File(plugin.getDataFolder(), "webhooks.yml");
        
        if (!webhookFile.exists()) {
            plugin.saveResource("webhooks.yml", false);
        }
        
        webhookConfig = YamlConfiguration.loadConfiguration(webhookFile);
        
        enabled = webhookConfig.getBoolean("webhook.enabled", false);
        webhookUrl = webhookConfig.getString("webhook.url", "");
        username = webhookConfig.getString("webhook.username", "JustTeams Bot");
        avatarUrl = webhookConfig.getString("webhook.avatar_url", "");
        serverName = webhookConfig.getString("webhook.server_name", "Minecraft Server");
        
        rateLimitingEnabled = webhookConfig.getBoolean("webhook.rate_limiting.enabled", true);
        maxPerMinute = webhookConfig.getInt("webhook.rate_limiting.max_per_minute", 20);
        minDelayMs = webhookConfig.getLong("webhook.rate_limiting.min_delay_ms", 1000);
        
        retryEnabled = webhookConfig.getBoolean("webhook.retry.enabled", true);
        maxRetryAttempts = webhookConfig.getInt("webhook.retry.max_attempts", 3);
        retryDelayMs = webhookConfig.getLong("webhook.retry.retry_delay_ms", 2000);
        
        showServerIp = webhookConfig.getBoolean("advanced.show_server_ip", false);
        showUuids = webhookConfig.getBoolean("advanced.show_uuids", false);
        connectionTimeout = webhookConfig.getInt("advanced.connection_timeout", 5000);
        readTimeout = webhookConfig.getInt("advanced.read_timeout", 5000);
        logErrors = webhookConfig.getBoolean("advanced.log_errors", true);
        logSuccess = webhookConfig.getBoolean("advanced.log_success", false);
        asyncSending = webhookConfig.getBoolean("advanced.async_sending", true);
        queueOnRateLimit = webhookConfig.getBoolean("advanced.queue_on_rate_limit", true);
        maxQueueSize = webhookConfig.getInt("advanced.max_queue_size", 50);
        
        if (enabled && (webhookUrl.isEmpty() || webhookUrl.contains("YOUR_WEBHOOK"))) {
            plugin.getLogger().warning("[Discord Webhook] Webhook is enabled but URL is not configured!");
            plugin.getLogger().warning("[Discord Webhook] Please set your webhook URL in webhooks.yml");
            enabled = false;
        }
        
        if (enabled) {
            plugin.getLogger().info("[Discord Webhook] Discord webhook notifications enabled");
            plugin.getLogger().info("[Discord Webhook] Server: " + serverName);
        }
    }
    
    public void reload() {
        loadConfiguration();
        plugin.getLogger().info("[Discord Webhook] Configuration reloaded");
    }
    
    private void startQueueProcessor() {
        executor.scheduleAtFixedRate(() -> {
            try {
                processQueue();
            } catch (Exception e) {
                if (logErrors) {
                    plugin.getLogger().warning("[Discord Webhook] Error in queue processor: " + e.getMessage());
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
    
    private void processQueue() {
        long now = System.currentTimeMillis();
        if (now - lastMinuteReset.get() >= 60000) {
            requestsThisMinute.set(0);
            lastMinuteReset.set(now);
        }
        
        while (!messageQueue.isEmpty() && canSendNow()) {
            WebhookMessage message = messageQueue.poll();
            if (message != null) {
                sendWebhookInternal(message);
            }
        }
    }
    
    private boolean canSendNow() {
        if (!rateLimitingEnabled) {
            return true;
        }
        
        long now = System.currentTimeMillis();
        
        if (requestsThisMinute.get() >= maxPerMinute) {
            return false;
        }
        
        if (now - lastRequestTime.get() < minDelayMs) {
            return false;
        }
        
        return true;
    }
    
    public void sendWebhook(String eventType, Map<String, String> placeholders) {
        if (!enabled) {
            return;
        }
        
        if (!webhookConfig.getBoolean("events." + eventType + ".enabled", false)) {
            return;
        }
        
        WebhookMessage message = new WebhookMessage(eventType, placeholders);
        
        if (asyncSending) {
            if (queueOnRateLimit) {
                if (messageQueue.size() < maxQueueSize) {
                    messageQueue.offer(message);
                } else {
                    if (logErrors) {
                        plugin.getLogger().warning("[Discord Webhook] Queue is full, dropping message: " + eventType);
                    }
                }
            } else {
                asyncExecutor.submit(() -> sendWebhookInternal(message));
            }
        } else {
            sendWebhookInternal(message);
        }
    }
    
    private void sendWebhookInternal(WebhookMessage message) {
        try {
           
            if (!canSendNow()) {
                if (queueOnRateLimit && message.retryCount < maxRetryAttempts) {
                    messageQueue.offer(message);
                }
                return;
            }
            
            JsonObject payload = buildWebhookPayload(message.eventType, message.placeholders);
            
            boolean success = sendToDiscord(payload);
            
            if (success) {
                requestsThisMinute.incrementAndGet();
                lastRequestTime.set(System.currentTimeMillis());
                
                if (logSuccess) {
                    plugin.getLogger().info("[Discord Webhook] Successfully sent: " + message.eventType);
                }
            } else {
                if (retryEnabled && message.retryCount < maxRetryAttempts) {
                    executor.schedule(() -> {
                        messageQueue.offer(message.withRetry());
                    }, retryDelayMs, TimeUnit.MILLISECONDS);
                    
                    if (logErrors) {
                        plugin.getLogger().warning("[Discord Webhook] Failed to send, will retry (" + 
                            (message.retryCount + 1) + "/" + maxRetryAttempts + "): " + message.eventType);
                    }
                }
            }
        } catch (Exception e) {
            if (logErrors) {
                plugin.getLogger().warning("[Discord Webhook] Error sending webhook: " + e.getMessage());
            }
        }
    }
    
    private JsonObject buildWebhookPayload(String eventType, Map<String, String> placeholders) {
        JsonObject payload = new JsonObject();
        
        if (!username.isEmpty()) {
            payload.addProperty("username", username);
        }
        if (!avatarUrl.isEmpty()) {
            payload.addProperty("avatar_url", avatarUrl);
        }
        
        JsonObject embed = new JsonObject();
        
        ConfigurationSection eventConfig = webhookConfig.getConfigurationSection("events." + eventType);
        if (eventConfig == null) {
            return payload; 
        }
        
        int color = eventConfig.getInt("color", 5814783);
        embed.addProperty("color", color);
        
        String title = eventConfig.getString("title", "Team Event");
        title = replacePlaceholders(title, placeholders);
        embed.addProperty("title", title);
        String description = eventConfig.getString("description", "");
        description = replacePlaceholders(description, placeholders);
        if (!description.isEmpty()) {
            embed.addProperty("description", description);
        }
        
        if (eventConfig.getBoolean("show_fields", false)) {
            JsonArray fields = new JsonArray();
            List<Map<?, ?>> fieldsList = eventConfig.getMapList("fields");
            
            for (Map<?, ?> fieldMap : fieldsList) {
                String fieldName = (String) fieldMap.get("name");
                String fieldValue = (String) fieldMap.get("value");
                boolean inline = fieldMap.containsKey("inline") ? (Boolean) fieldMap.get("inline") : false;
                
                fieldName = replacePlaceholders(fieldName, placeholders);
                fieldValue = replacePlaceholders(fieldValue, placeholders);
                
                JsonObject field = new JsonObject();
                field.addProperty("name", fieldName);
                field.addProperty("value", fieldValue);
                field.addProperty("inline", inline);
                fields.add(field);
            }
            
            if (fields.size() > 0) {
                embed.add("fields", fields);
            }
        }
        
        String footer = eventConfig.getString("footer", "");
        footer = replacePlaceholders(footer, placeholders);
        if (showServerIp) {
            String serverIp = Bukkit.getIp();
            int serverPort = Bukkit.getPort();
            if (!serverIp.isEmpty()) {
                footer += " | " + serverIp + ":" + serverPort;
            }
        }
        if (!footer.isEmpty()) {
            JsonObject footerObj = new JsonObject();
            footerObj.addProperty("text", footer);
            embed.add("footer", footerObj);
        }
        
        if (eventConfig.getBoolean("timestamp", true)) {
            embed.addProperty("timestamp", Instant.now().toString());
        }
        
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        
        return payload;
    }
    
    private String replacePlaceholders(String text, Map<String, String> placeholders) {
        Map<String, String> allPlaceholders = new HashMap<>(placeholders);
        allPlaceholders.putIfAbsent("server", serverName);
        allPlaceholders.putIfAbsent("time", Instant.now().toString());
        
        for (Map.Entry<String, String> entry : allPlaceholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        
        return text;
    }
    
    private boolean sendToDiscord(JsonObject payload) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(webhookUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "JustTeams-Webhook/1.0");
            connection.setDoOutput(true);
            connection.setConnectTimeout(connectionTimeout);
            connection.setReadTimeout(readTimeout);
            
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            int responseCode = connection.getResponseCode();
            
            if (responseCode == 204) {
                return true;
            } else if (responseCode == 429) {
                if (logErrors) {
                    plugin.getLogger().warning("[Discord Webhook] Rate limited by Discord (429)");
                }
                return false;
            } else if (responseCode >= 400) {
                if (logErrors) {
                    plugin.getLogger().warning("[Discord Webhook] Discord returned error code: " + responseCode);
                }
                return false;
            }
            
            return true;
        } catch (Exception e) {
            if (logErrors) {
                plugin.getLogger().warning("[Discord Webhook] Failed to send webhook: " + e.getMessage());
            }
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    public void shutdown() {
        executor.shutdown();
        asyncExecutor.shutdown();
        
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        plugin.getLogger().info("[Discord Webhook] Webhook manager shut down");
    }
    
    public int getQueueSize() {
        return messageQueue.size();
    }
    
    public int getRequestsThisMinute() {
        return requestsThisMinute.get();
    }
}
