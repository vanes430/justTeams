package eu.kotori.justTeams.util;

import eu.kotori.justTeams.JustTeams;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.List;

public class FeatureRestrictionManager {
    
    private final JustTeams plugin;
    
    public FeatureRestrictionManager(JustTeams plugin) {
        this.plugin = plugin;
    }
    
    public boolean isFeatureAllowed(Player player, String feature) {
        return !plugin.getConfigManager().isFeatureDisabledInWorld(feature, player.getWorld().getName());
    }
    
    public boolean canAffordAndPay(Player player, String feature) {
        if (plugin == null || plugin.getConfigManager() == null) {
            return true;
        }
        
        if (!plugin.getConfigManager().isFeatureCostsEnabled()) {
            return true;
        }
        
        if (plugin.getConfigManager().isEconomyCostsEnabled()) {
            double cost = plugin.getConfigManager().getFeatureEconomyCost(feature);
            if (cost > 0) {
                Economy economy = plugin.getEconomy();
                if (economy == null) {
                    plugin.getLogger().warning("Economy cost configured but Vault not found!");
                    return true; 
                }
                
                if (!economy.has(player, cost)) {
                    plugin.getMessageManager().sendMessage(player, "insufficient_funds",
                        Placeholder.unparsed("cost", economy.format(cost)),
                        Placeholder.unparsed("balance", economy.format(economy.getBalance(player))));
                    return false;
                }
                
                if (!economy.withdrawPlayer(player, cost).transactionSuccess()) {
                    plugin.getMessageManager().sendMessage(player, "economy_error");
                    return false;
                }
                
                plugin.getMessageManager().sendMessage(player, "economy_charged",
                    Placeholder.unparsed("cost", economy.format(cost)),
                    Placeholder.unparsed("feature", feature));
            }
        }
        
        if (plugin.getConfigManager().isItemCostsEnabled()) {
            List<String> itemCosts = plugin.getConfigManager().getFeatureItemCosts(feature);
            if (!itemCosts.isEmpty()) {
                List<ItemStack> requiredItems = parseItemCosts(itemCosts);
                
                if (!hasRequiredItems(player, requiredItems)) {
                    plugin.getMessageManager().sendMessage(player, "insufficient_items",
                        Placeholder.unparsed("required", formatRequiredItems(requiredItems)),
                        Placeholder.unparsed("current", "0"));
                    return false;
                }
                
                if (plugin.getConfigManager().shouldConsumeItemsOnUse()) {
                    consumeItems(player, requiredItems);
                    plugin.getMessageManager().sendMessage(player, "items_taken",
                        Placeholder.unparsed("items", formatRequiredItems(requiredItems)),
                        Placeholder.unparsed("feature", feature));
                }
            }
        }
        
        return true;
    }
    private List<ItemStack> parseItemCosts(List<String> itemCosts) {
        List<ItemStack> items = new ArrayList<>();
        
        for (String itemCost : itemCosts) {
            String[] parts = itemCost.split(":");
            if (parts.length != 2) {
                plugin.getLogger().warning("Invalid item cost format: " + itemCost + " (expected MATERIAL:AMOUNT)");
                continue;
            }
            
            try {
                Material material = Material.valueOf(parts[0].toUpperCase());
                int amount = Integer.parseInt(parts[1]);
                items.add(new ItemStack(material, amount));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material or amount in item cost: " + itemCost);
            }
        }
        
        return items;
    }
    
    private boolean hasRequiredItems(Player player, List<ItemStack> requiredItems) {
        for (ItemStack required : requiredItems) {
            int playerAmount = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == required.getType()) {
                    playerAmount += item.getAmount();
                }
            }
            
            if (playerAmount < required.getAmount()) {
                return false;
            }
        }
        
        return true;
    }
    
    private void consumeItems(Player player, List<ItemStack> requiredItems) {
        for (ItemStack required : requiredItems) {
            int remaining = required.getAmount();
            
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == required.getType() && remaining > 0) {
                    int toRemove = Math.min(remaining, item.getAmount());
                    item.setAmount(item.getAmount() - toRemove);
                    remaining -= toRemove;
                    
                    if (item.getAmount() <= 0) {
                        player.getInventory().remove(item);
                    }
                }
            }
        }
        
        player.updateInventory();
    }
    
    private String formatRequiredItems(List<ItemStack> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            sb.append(item.getAmount()).append("x ").append(formatMaterialName(item.getType()));
            if (i < items.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }
    
    private String formatMaterialName(Material material) {
        String name = material.name().toLowerCase().replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }
    
    public String getFeatureCostInfo(String feature) {
        if (!plugin.getConfigManager().isFeatureCostsEnabled()) {
            return "";
        }
        
        StringBuilder info = new StringBuilder();
        
        if (plugin.getConfigManager().isEconomyCostsEnabled()) {
            double cost = plugin.getConfigManager().getFeatureEconomyCost(feature);
            if (cost > 0) {
                Economy economy = plugin.getEconomy();
                if (economy != null) {
                    info.append("Cost: ").append(economy.format(cost));
                }
            }
        }
        
        if (plugin.getConfigManager().isItemCostsEnabled()) {
            List<String> itemCosts = plugin.getConfigManager().getFeatureItemCosts(feature);
            if (!itemCosts.isEmpty()) {
                List<ItemStack> requiredItems = parseItemCosts(itemCosts);
                if (info.length() > 0) {
                    info.append(" + ");
                }
                info.append("Items: ").append(formatRequiredItems(requiredItems));
            }
        }
        
        return info.toString();
    }
}