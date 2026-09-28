package de.lovinoes.papernetworkchat;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The wire format between VelocityNetworkChat on the proxy and this plugin. The proxy's copy is
 * de.lovinoes.velocitynetworkchat.backend.Protocol and the two must agree byte for byte, which is
 * why both plugins always have to be updated together.
 *
 * The proxy asks, this plugin answers, over the one channel:
 * <ul>
 *   <li>NEARBY: who is within range of the sender, for distance-limited chat</li>
 *   <li>SHOWCASE: the sender's held item, inventory and position, for [item], [inv] and [pos]</li>
 * </ul>
 *
 * Nothing here depends on Bukkit, so the format can be tested on its own.
 */
public final class Protocol {

    public static final byte REQUEST_NEARBY = 1;
    public static final byte REQUEST_SHOWCASE = 2;
    public static final byte RESPONSE_NEARBY = 101;
    public static final byte RESPONSE_SHOWCASE = 102;

    public static final int ITEM = 1;
    public static final int INVENTORY = 2;
    public static final int POSITION = 4;

    public static final byte HOTBAR = 0;
    public static final byte STORAGE = 1;
    public static final byte ARMOR = 2;
    public static final byte OFFHAND = 3;

    /**
     * Per string. A component's JSON over this is replaced by its plain text, which is always
     * small. Without a cap a single renamed item could carry enough JSON to swell the chat line
     * sent to every recipient into something that disconnects them.
     */
    public static final int MAX_JSON_BYTES = 4096;

    /** Plain text is only the fallback, so it only needs to be recognisable. */
    public static final int MAX_PLAIN_CHARS = 256;

    public static final int MAX_LORE_LINES = 20;
    public static final int MAX_ENCHANTMENTS = 20;

    /**
     * Across all the lore of one item, and across all the names in one inventory. Past it, the
     * remaining entries are sent as plain text only, so a pathological item cannot make the
     * response large however many long lines it has.
     */
    public static final int JSON_BUDGET_BYTES = 24576;

    private Protocol() {
    }

    /**
     * Length-prefixed UTF-8. DataOutputStream#writeUTF caps a string at 65535 bytes and throws
     * past it, which would lose the whole response over one long line.
     */
    public static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static String truncatePlain(String plain) {
        return plain.length() <= MAX_PLAIN_CHARS ? plain : plain.substring(0, MAX_PLAIN_CHARS);
    }

    /**
     * A component as JSON when it fits, and always as plain text. The JSON is dropped rather
     * than cut when it is too long, since half a JSON document is not a component.
     *
     * @return how many JSON bytes were written, for keeping a running budget
     */
    public static int writeComponent(DataOutputStream out, String json, String plain, boolean allowJson)
            throws IOException {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        boolean keepJson = allowJson && jsonBytes.length <= MAX_JSON_BYTES;
        writeString(out, keepJson ? json : "");
        writeString(out, truncatePlain(plain));
        return keepJson ? jsonBytes.length : 0;
    }

    /** One line of a list, such as a lore line or an enchantment, as JSON and plain text. */
    public record Line(String json, String plain) {
    }

    /** Everything the proxy shows about a held item. */
    public record Item(String material, int amount, Line name, java.util.List<Line> lore,
                       java.util.List<Line> enchantments, int damage, int maxDamage, boolean unbreakable) {
    }

    public static void writeItem(DataOutputStream out, Item item) throws IOException {
        writeString(out, item.material());
        out.writeInt(item.amount());
        writeComponent(out, item.name().json(), item.name().plain(), true);

        int budget = JSON_BUDGET_BYTES;
        int loreCount = Math.min(item.lore().size(), MAX_LORE_LINES);
        out.writeInt(loreCount);
        for (int i = 0; i < loreCount; i++) {
            Line line = item.lore().get(i);
            budget -= writeComponent(out, line.json(), line.plain(), budget > 0);
        }

        int enchantmentCount = Math.min(item.enchantments().size(), MAX_ENCHANTMENTS);
        out.writeInt(enchantmentCount);
        for (int i = 0; i < enchantmentCount; i++) {
            Line line = item.enchantments().get(i);
            budget -= writeComponent(out, line.json(), line.plain(), budget > 0);
        }

        out.writeInt(item.damage());
        out.writeInt(item.maxDamage());
        out.writeBoolean(item.unbreakable());
    }

    /** One slot of an inventory listing. Only the name is sent, never lore. */
    public record Entry(byte group, String material, int amount, Line name) {
    }

    public record Position(String world, int x, int y, int z) {
    }

    /**
     * @param flags which parts were asked for. Each is answered even when there is nothing to
     *              show, such as an empty hand, so the proxy never mistakes "nothing held" for
     *              "no answer".
     * @param held  null for an empty hand
     */
    public static byte[] showcaseResponse(long requestId, int flags, Item held,
                                          java.util.List<Entry> inventory, Position position) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(RESPONSE_SHOWCASE);
            out.writeLong(requestId);
            out.writeInt(flags);

            if ((flags & ITEM) != 0) {
                out.writeBoolean(held != null);
                if (held != null) {
                    writeItem(out, held);
                }
            }

            if ((flags & INVENTORY) != 0) {
                out.writeInt(inventory.size());
                int budget = JSON_BUDGET_BYTES;
                for (Entry entry : inventory) {
                    out.writeByte(entry.group());
                    writeString(out, entry.material());
                    out.writeInt(entry.amount());
                    budget -= writeComponent(out, entry.name().json(), entry.name().plain(), budget > 0);
                }
            }

            if ((flags & POSITION) != 0) {
                writeString(out, position.world());
                out.writeInt(position.x());
                out.writeInt(position.y());
                out.writeInt(position.z());
            }
        }
        return buffer.toByteArray();
    }

    public static byte[] nearbyResponse(long requestId, java.util.List<java.util.UUID> players) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(RESPONSE_NEARBY);
            out.writeLong(requestId);
            out.writeInt(players.size());
            for (java.util.UUID player : players) {
                out.writeLong(player.getMostSignificantBits());
                out.writeLong(player.getLeastSignificantBits());
            }
        }
        return buffer.toByteArray();
    }
}
