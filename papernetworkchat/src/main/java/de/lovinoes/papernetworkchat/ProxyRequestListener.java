package de.lovinoes.papernetworkchat;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answers the proxy's questions about a player: who is near them, and what they are holding,
 * carrying and standing on.
 *
 * The proxy owns every chat channel and renders every message; it simply cannot see a backend's
 * world. So it asks, over the plugin channel, and renders the answer itself.
 *
 * Every request arrives through the connection of the player it is about, and is answered back
 * through that same connection. Anything about somebody else is ignored.
 */
public final class ProxyRequestListener implements PluginMessageListener {

    private final Plugin plugin;
    private final String channel;

    public ProxyRequestListener(Plugin plugin, String channel) {
        this.plugin = plugin;
        this.channel = channel;
    }

    @Override
    public void onPluginMessageReceived(String incomingChannel, Player carrier, byte[] message) {
        byte type;
        long requestId;
        int argument;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            type = in.readByte();
            requestId = in.readLong();
            argument = in.readInt();
        } catch (IOException e) {
            plugin.getLogger().warning("Ignoring a malformed request from the proxy: " + e.getMessage());
            return;
        }

        UUID carrierId = carrier.getUniqueId();
        // Locations and inventories are main-thread only; plugin messages arrive on a netty thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(carrierId);
            if (player == null || !player.isOnline()) {
                // They left or switched servers in between. The proxy times out and moves on.
                return;
            }
            try {
                byte[] response = switch (type) {
                    case Protocol.REQUEST_NEARBY -> Protocol.nearbyResponse(requestId, nearby(player, argument));
                    case Protocol.REQUEST_SHOWCASE -> showcase(player, requestId, argument);
                    default -> null;
                };
                if (response == null) {
                    plugin.getLogger().warning("Ignoring a request of unknown type " + type
                            + ". Are PaperNetworkChat and VelocityNetworkChat the same version?");
                    return;
                }
                player.sendPluginMessage(plugin, channel, response);
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("Could not answer the proxy about " + player.getName() + ": " + e);
            }
        });
    }

    /**
     * Everyone in the sender's world within range, the sender included. The proxy decides who
     * of these may actually read the channel; this only knows about distance.
     */
    private List<UUID> nearby(Player sender, int range) {
        Location origin = sender.getLocation();
        double rangeSquared = (double) range * range;
        List<UUID> found = new ArrayList<>();
        for (Player other : sender.getWorld().getPlayers()) {
            if (other.getLocation().distanceSquared(origin) <= rangeSquared) {
                found.add(other.getUniqueId());
            }
        }
        return found;
    }

    private byte[] showcase(Player player, long requestId, int flags) throws IOException {
        PlayerInventory inventory = player.getInventory();

        Protocol.Item held = null;
        if ((flags & Protocol.ITEM) != 0) {
            ItemStack hand = inventory.getItemInMainHand();
            held = hand.isEmpty() ? null : item(hand);
        }

        List<Protocol.Entry> entries = new ArrayList<>();
        if ((flags & Protocol.INVENTORY) != 0) {
            ItemStack[] storage = inventory.getStorageContents();
            for (int slot = 0; slot < storage.length; slot++) {
                // Slots 0 to 8 are the hotbar; the rest of the storage contents is the backpack.
                addEntry(entries, slot < 9 ? Protocol.HOTBAR : Protocol.STORAGE, storage[slot]);
            }
            ItemStack[] armor = inventory.getArmorContents();
            // Armour comes back boots first; helmet first reads the way a player looks.
            for (int slot = armor.length - 1; slot >= 0; slot--) {
                addEntry(entries, Protocol.ARMOR, armor[slot]);
            }
            addEntry(entries, Protocol.OFFHAND, inventory.getItemInOffHand());
        }

        Protocol.Position position = null;
        if ((flags & Protocol.POSITION) != 0) {
            Location location = player.getLocation();
            position = new Protocol.Position(location.getWorld().getName(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        return Protocol.showcaseResponse(requestId, flags, held, entries, position);
    }

    private static void addEntry(List<Protocol.Entry> entries, byte group, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        entries.add(new Protocol.Entry(group, stack.getType().getKey().toString(), stack.getAmount(),
                line(stack.effectiveName())));
    }

    private static Protocol.Item item(ItemStack stack) {
        List<Protocol.Line> lore = new ArrayList<>();
        List<Component> loreLines = stack.lore();
        if (loreLines != null) {
            for (Component line : loreLines) {
                lore.add(line(line));
            }
        }

        List<Protocol.Line> enchantments = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> enchantment : stack.getEnchantments().entrySet()) {
            enchantments.add(line(enchantment.getKey().displayName(enchantment.getValue())));
        }

        // An item without a maximum damage simply is not damageable, so 0 means "no durability".
        Integer maxDamage = stack.getData(DataComponentTypes.MAX_DAMAGE);
        Integer damage = stack.getData(DataComponentTypes.DAMAGE);

        return new Protocol.Item(stack.getType().getKey().toString(), stack.getAmount(),
                line(stack.effectiveName()), lore, enchantments,
                damage == null ? 0 : damage, maxDamage == null ? 0 : maxDamage,
                stack.hasData(DataComponentTypes.UNBREAKABLE));
    }

    private static Protocol.Line line(Component component) {
        return new Protocol.Line(GsonComponentSerializer.gson().serialize(component),
                PlainTextComponentSerializer.plainText().serialize(component));
    }
}
