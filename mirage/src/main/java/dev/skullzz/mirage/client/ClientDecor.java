package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import dev.skullzz.mirage.Mirage;

/**
 * Item frames and armour stands that exist only on your client, for dressing a base up with
 * gear you do not have.
 *
 * <p>Entities are client-only, so they vanish for everyone else and cost the server nothing.
 * They are respawned whenever their chunk comes back, since the client discards entities in
 * chunks it unloads.
 */
public final class ClientDecor {
    private static final int RESPAWN_CHECK_TICKS = 20;
    /** Client-only ids, from the top of the range so they miss the server's. */
    private static int nextEntityId = Integer.MAX_VALUE - 20000;

    private static final List<Piece> pieces = new ArrayList<>();
    private static long tick;

    /** One placed decoration. Items are held as ids so the entity can be rebuilt any time. */
    private static final class Piece {
        final boolean frame;
        final BlockPos pos;
        final Direction facing;
        /** A frame has one item; a stand has helmet, chestplate, leggings, boots. */
        final List<String> items;
        Entity entity;

        Piece(boolean frame, BlockPos pos, Direction facing, List<String> items) {
            this.frame = frame;
            this.pos = pos.toImmutable();
            this.facing = facing;
            this.items = items;
        }
    }

    private ClientDecor() {
    }

    public static int count() {
        return pieces.size();
    }

    public static List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (Piece piece : pieces) {
            lines.add((piece.frame ? "frame " : "stand ") + piece.pos.getX() + " "
                    + piece.pos.getY() + " " + piece.pos.getZ() + " -> " + String.join(", ", piece.items));
        }
        return lines;
    }

    // ------------------------------------------------------------------ placing

    public static void addFrame(BlockPos pos, Direction facing, String itemId) {
        pieces.add(new Piece(true, pos, facing, List.of(itemId)));
        SelfFakes.save();
    }

    /** @return false if the material has no armour pieces. */
    public static boolean addStand(BlockPos pos, String material) {
        List<String> armour = armourFor(material);
        if (armour.isEmpty()) return false;

        pieces.add(new Piece(false, pos, Direction.NORTH, armour));
        SelfFakes.save();
        return true;
    }

    /** netherite, diamond, iron, golden, chainmail or leather; else the item on the head. */
    private static List<String> armourFor(String material) {
        String cleaned = material.toLowerCase().trim();
        String[] suffixes = {"_helmet", "_chestplate", "_leggings", "_boots"};

        List<String> pieceIds = new ArrayList<>();
        for (String suffix : suffixes) {
            if (SelfFakes.lookupItem(cleaned + suffix) == null) {
                pieceIds.clear();
                break;
            }
            pieceIds.add(cleaned + suffix);
        }
        if (!pieceIds.isEmpty()) return pieceIds;

        // Not a set: treat it as one item worn on the head.
        if (SelfFakes.lookupItem(cleaned) != null) {
            return List.of(cleaned, "", "", "");
        }
        return List.of();
    }

    public static int removeNear(BlockPos pos, double radius) {
        double squared = radius * radius;
        int removed = 0;

        for (int index = pieces.size() - 1; index >= 0; index--) {
            Piece piece = pieces.get(index);
            if (piece.pos.getSquaredDistance(pos) > squared) continue;

            despawn(piece);
            pieces.remove(index);
            removed++;
        }
        if (removed > 0) SelfFakes.save();
        return removed;
    }

    public static void clear() {
        for (Piece piece : pieces) despawn(piece);
        pieces.clear();
        SelfFakes.save();
    }

    private static void despawn(Piece piece) {
        if (piece.entity != null && !piece.entity.isRemoved()) piece.entity.discard();
        piece.entity = null;
    }

    /** Leaving a world takes every client entity with it. */
    public static void reset() {
        for (Piece piece : pieces) piece.entity = null;
    }

    // --------------------------------------------------------------------- tick

    /** Takes the decor out of the world but keeps it listed, ready to come straight back. */
    public static void hide() {
        for (Piece piece : pieces) despawn(piece);
    }

    public static void tick(ClientWorld world) {
        if (world == null || pieces.isEmpty()) return;
        if (!SelfFakes.enabled()) {
            hide();
            return;
        }
        if (++tick % RESPAWN_CHECK_TICKS != 0) return;

        for (Piece piece : pieces) {
            if (piece.entity != null && !piece.entity.isRemoved()) continue;
            if (!world.isChunkLoaded(piece.pos.getX() >> 4, piece.pos.getZ() >> 4)) continue;
            spawn(world, piece);
        }
    }

    private static void spawn(ClientWorld world, Piece piece) {
        try {
            Entity entity = piece.frame ? buildFrame(world, piece) : buildStand(world, piece);
            if (entity == null) return;

            entity.setId(nextEntityId--);
            world.addEntity(entity);
            piece.entity = entity;
        } catch (RuntimeException e) {
            Mirage.LOGGER.warn("Mirage could not place decor at {}: {}", piece.pos, e.toString());
        }
    }

    private static Entity buildFrame(ClientWorld world, Piece piece) {
        Item item = SelfFakes.lookupItem(piece.items.get(0));
        if (item == null) return null;

        ItemFrameEntity frame = new ItemFrameEntity(world, piece.pos, piece.facing);
        frame.setHeldItemStack(new ItemStack(item));
        return frame;
    }

    private static Entity buildStand(ClientWorld world, Piece piece) {
        ArmorStandEntity stand = new ArmorStandEntity(world,
                piece.pos.getX() + 0.5, piece.pos.getY(), piece.pos.getZ() + 0.5);

        EquipmentSlot[] slots = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        };
        for (int index = 0; index < slots.length && index < piece.items.size(); index++) {
            String id = piece.items.get(index);
            if (id == null || id.isEmpty()) continue;

            Item item = SelfFakes.lookupItem(id);
            if (item != null) stand.equipStack(slots[index], new ItemStack(item));
        }
        return stand;
    }

    // -------------------------------------------------------------- persistence

    public static void save(JsonObject root) {
        JsonArray array = new JsonArray();
        for (Piece piece : pieces) {
            JsonObject json = new JsonObject();
            json.addProperty("frame", piece.frame);
            json.addProperty("pos", piece.pos.getX() + "," + piece.pos.getY() + "," + piece.pos.getZ());
            json.addProperty("facing", piece.facing.name());

            JsonArray items = new JsonArray();
            for (String id : piece.items) items.add(id);
            json.add("items", items);

            array.add(json);
        }
        root.add("decor", array);
    }

    public static void load(JsonObject root) {
        pieces.clear();
        if (!root.has("decor")) return;

        for (JsonElement element : root.getAsJsonArray("decor")) {
            try {
                JsonObject json = element.getAsJsonObject();
                String[] parts = json.get("pos").getAsString().split(",");
                if (parts.length != 3) continue;

                BlockPos pos = new BlockPos(Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                Direction facing = Direction.valueOf(json.get("facing").getAsString());

                List<String> items = new ArrayList<>();
                for (JsonElement item : json.getAsJsonArray("items")) items.add(item.getAsString());

                pieces.add(new Piece(json.get("frame").getAsBoolean(), pos, facing, items));
            } catch (RuntimeException ignored) {
                // one unreadable entry should not lose the rest
            }
        }
    }
}
