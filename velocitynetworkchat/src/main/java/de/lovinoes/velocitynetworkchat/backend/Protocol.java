package de.lovinoes.velocitynetworkchat.backend;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The wire format between this plugin and PaperNetworkChat on each backend. The backend's copy is
 * de.lovinoes.papernetworkchat.Protocol and the two must agree byte for byte, which is why both
 * plugins always have to be updated together.
 *
 * Everything read here comes from a backend, and every length is checked before anything is
 * allocated for it. A corrupt or hostile message fails with an IOException, never with an
 * attempt to allocate however many bytes it claims to hold.
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

    /** Above anything the backend sends, which caps JSON at 4096 bytes per string. */
    static final int MAX_STRING_BYTES = 16384;

    /** Above the backend's 20, and above a full inventory's 41 slots. */
    static final int MAX_LIST = 64;

    /** Far above any real server's population, and small enough to reject absurd claims. */
    static final int MAX_PLAYERS = 4096;

    private Protocol() {
    }

    public static byte[] request(byte type, long requestId, int argument) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(type);
            out.writeLong(requestId);
            out.writeInt(argument);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("a string claims " + length + " bytes");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static int readCount(DataInputStream in, int max) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) {
            throw new IOException("a list claims " + count + " entries");
        }
        return count;
    }

    /** A component as the backend sent it: JSON when it fitted, and always plain text. */
    public record Line(String json, String plain) {
    }

    public record Item(String material, int amount, Line name, List<Line> lore, List<Line> enchantments,
                       int damage, int maxDamage, boolean unbreakable) {
    }

    public record Entry(byte group, String material, int amount, Line name) {
    }

    public record Position(String world, int x, int y, int z) {
    }

    /**
     * @param flags which parts were answered
     * @param held  null when the hand was empty, or when the item was not asked for
     */
    public record Showcase(int flags, Item held, List<Entry> inventory, Position position) {

        public boolean answered(int part) {
            return (flags & part) != 0;
        }
    }

    static Line readLine(DataInputStream in) throws IOException {
        return new Line(readString(in), readString(in));
    }

    static Item readItem(DataInputStream in) throws IOException {
        String material = readString(in);
        int amount = in.readInt();
        Line name = readLine(in);

        int loreCount = readCount(in, MAX_LIST);
        List<Line> lore = new ArrayList<>(loreCount);
        for (int i = 0; i < loreCount; i++) {
            lore.add(readLine(in));
        }

        int enchantmentCount = readCount(in, MAX_LIST);
        List<Line> enchantments = new ArrayList<>(enchantmentCount);
        for (int i = 0; i < enchantmentCount; i++) {
            enchantments.add(readLine(in));
        }

        int damage = in.readInt();
        int maxDamage = in.readInt();
        boolean unbreakable = in.readBoolean();
        return new Item(material, amount, name, lore, enchantments, damage, maxDamage, unbreakable);
    }

    /** Reads the body of a showcase response, after its type byte and request id. */
    public static Showcase readShowcase(DataInputStream in) throws IOException {
        int flags = in.readInt();

        Item held = null;
        if ((flags & ITEM) != 0 && in.readBoolean()) {
            held = readItem(in);
        }

        List<Entry> inventory = new ArrayList<>();
        if ((flags & INVENTORY) != 0) {
            int count = readCount(in, MAX_LIST);
            for (int i = 0; i < count; i++) {
                byte group = in.readByte();
                String material = readString(in);
                int amount = in.readInt();
                inventory.add(new Entry(group, material, amount, readLine(in)));
            }
        }

        Position position = null;
        if ((flags & POSITION) != 0) {
            position = new Position(readString(in), in.readInt(), in.readInt(), in.readInt());
        }
        return new Showcase(flags, held, inventory, position);
    }

    /** Reads the body of a nearby response, after its type byte and request id. */
    public static List<UUID> readNearby(DataInputStream in) throws IOException {
        int count = readCount(in, MAX_PLAYERS);
        List<UUID> players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            players.add(new UUID(in.readLong(), in.readLong()));
        }
        return players;
    }
}
