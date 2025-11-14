package eu.kotori.justTeams.redis;

import eu.kotori.justTeams.JustTeams;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
public class RedisManager {
    private final JustTeams plugin;
    private JedisPool jedisPool;
    private ExecutorService executorService;
    private TeamMessageSubscriber messageSubscriber;
    private TeamUpdateSubscriber updateSubscriber;
    private volatile boolean enabled = false;
    private volatile boolean connected = false;
    
    private static final String CHANNEL_TEAM_CHAT = "justteams:chat";
    private static final String CHANNEL_TEAM_UPDATES = "justteams:updates";
    private static final String CHANNEL_TEAM_MESSAGES = "justteams:messages";
    
    public RedisManager(JustTeams plugin) {
        this.plugin = plugin;
    }
    
    public void initialize() {
        if (!plugin.getConfigManager().isRedisEnabled()) {
            plugin.getLogger().info("Redis is disabled in configuration");
            enabled = false;
            return;
        }
        
        try {
            String host = plugin.getConfigManager().getRedisHost();
            int port = plugin.getConfigManager().getRedisPort();
            String password = plugin.getConfigManager().getRedisPassword();
            boolean useSSL = plugin.getConfigManager().isRedisSslEnabled();
            int timeout = plugin.getConfigManager().getRedisTimeout();
            
            plugin.getLogger().info("Initializing Redis connection to " + host + ":" + port + "...");
            
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(20); 
            poolConfig.setMaxIdle(10); 
            poolConfig.setMinIdle(2); 
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestOnReturn(true);
            poolConfig.setTestWhileIdle(true);
            poolConfig.setMinEvictableIdleTime(Duration.ofSeconds(60));
            poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
            poolConfig.setBlockWhenExhausted(true);
            poolConfig.setMaxWait(Duration.ofSeconds(2));
            
            if (password != null && !password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, password, useSSL);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, useSSL);
            }
            
            try (Jedis jedis = jedisPool.getResource()) {
                String pong = jedis.ping();
                if ("PONG".equals(pong)) {
                    connected = true;
                    enabled = true;
                    plugin.getLogger().info("✓ Redis connection successful! PING returned PONG");
                } else {
                    throw new JedisException("Unexpected PING response: " + pong);
                }
            }
            
            executorService = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "JustTeams-Redis-Subscriber");
                t.setDaemon(true);
                return t;
            });
            
            startSubscribers();
            
            plugin.getLogger().info("✓ Redis Pub/Sub initialized successfully");
            plugin.getLogger().info("  Channels: " + CHANNEL_TEAM_CHAT + ", " + CHANNEL_TEAM_UPDATES + ", " + CHANNEL_TEAM_MESSAGES);
            plugin.getLogger().info("  Mode: INSTANT (< 100ms delivery)");
            
        } catch (Exception e) {
            plugin.getLogger().severe("✗ Failed to initialize Redis connection: " + e.getMessage());
            plugin.getLogger().severe("  Will fall back to MySQL polling mode");
            enabled = false;
            connected = false;
            
            if (jedisPool != null) {
                jedisPool.close();
                jedisPool = null;
            }
        }
    }
    
    private void startSubscribers() {
        messageSubscriber = new TeamMessageSubscriber(plugin);
        executorService.submit(() -> {
            while (enabled && !Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    plugin.getLogger().info("Starting Redis message subscriber...");
                    jedis.subscribe(messageSubscriber, CHANNEL_TEAM_CHAT, CHANNEL_TEAM_MESSAGES);
                } catch (Exception e) {
                    plugin.getLogger().warning("Redis message subscriber disconnected: " + e.getMessage());
                    if (enabled) {
                        try {
                            Thread.sleep(5000); 
                        } catch (InterruptedException ex) {
                            break;
                        }
                    }
                }
            }
        });
        
        updateSubscriber = new TeamUpdateSubscriber(plugin);
        executorService.submit(() -> {
            while (enabled && !Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    plugin.getLogger().info("Starting Redis update subscriber...");
                    jedis.subscribe(updateSubscriber, CHANNEL_TEAM_UPDATES);
                } catch (Exception e) {
                    plugin.getLogger().warning("Redis update subscriber disconnected: " + e.getMessage());
                    if (enabled) {
                        try {
                            Thread.sleep(5000); 
                        } catch (InterruptedException ex) {
                            break;
                        }
                    }
                }
            }
        });
    }
    
    public CompletableFuture<Boolean> publishTeamChat(int teamId, String playerUuid, String playerName, String message) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(false);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String payload = String.format("%d|%s|%s|%s|%d", 
                    teamId, playerUuid, playerName, message, System.currentTimeMillis());
                long subscribers = jedis.publish(CHANNEL_TEAM_CHAT, payload);
                plugin.getLogger().info("Published team chat to " + subscribers + " servers (Redis)");
                return subscribers > 0;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to publish team chat via Redis: " + e.getMessage());
                return false;
            }
        });
    }
    public CompletableFuture<Boolean> publishTeamMessage(int teamId, String playerUuid, String playerName, String message) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(false);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String payload = String.format("%d|%s|%s|%s|%d", 
                    teamId, playerUuid, playerName, message, System.currentTimeMillis());
                long subscribers = jedis.publish(CHANNEL_TEAM_MESSAGES, payload);
                plugin.getLogger().info("Published team message to " + subscribers + " servers (Redis)");
                return subscribers > 0;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to publish team message via Redis: " + e.getMessage());
                return false;
            }
        });
    }
    
    public CompletableFuture<Boolean> publishTeamUpdate(int teamId, String updateType, String playerUuid, String data) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(false);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String payload = String.format("%d|%s|%s|%s|%d", 
                    teamId, updateType, playerUuid, data, System.currentTimeMillis());
                long subscribers = jedis.publish(CHANNEL_TEAM_UPDATES, payload);
                plugin.getLogger().info("Published " + updateType + " to " + subscribers + " servers (Redis)");
                return subscribers > 0;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to publish team update via Redis: " + e.getMessage());
                return false;
            }
        });
    }
    public boolean isEnabled() {
        return enabled;
    }
    
    public boolean isAvailable() {
        return enabled && connected && jedisPool != null && !jedisPool.isClosed();
    }
    
    public String getConnectionStatus() {
        if (!enabled) return "DISABLED";
        if (!connected) return "DISCONNECTED";
        if (jedisPool == null || jedisPool.isClosed()) return "CLOSED";
        
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            return "CONNECTED";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    public void shutdown() {
        plugin.getLogger().info("Shutting down Redis connection...");
        enabled = false;
        connected = false;
        
        if (messageSubscriber != null) {
            try {
                messageSubscriber.unsubscribe();
            } catch (Exception e) {
                plugin.getLogger().warning("Error unsubscribing message subscriber: " + e.getMessage());
            }
        }
        
        if (updateSubscriber != null) {
            try {
                updateSubscriber.unsubscribe();
            } catch (Exception e) {
                plugin.getLogger().warning("Error unsubscribing update subscriber: " + e.getMessage());
            }
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
        
        plugin.getLogger().info("Redis connection closed");
    }
    
    public String getPoolStats() {
        if (jedisPool == null || jedisPool.isClosed()) {
            return "Pool: CLOSED";
        }
        
        return String.format("Pool: %d active, %d idle, %d waiters",
            jedisPool.getNumActive(),
            jedisPool.getNumIdle(),
            jedisPool.getNumWaiters());
    }
}
