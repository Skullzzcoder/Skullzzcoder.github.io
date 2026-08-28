package dev.skullzz.mirage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Mirage shows players things that are not there.
 *
 * <p>Nothing here touches the real world state: ghost items are slot-update packets sent to
 * one client, and the server's own inventories are never modified. The server stays
 * authoritative, so a victim clicking a ghost simply gets resynced and the item pops.
 */
public class Mirage implements ModInitializer {
    public static final String MOD_ID = "mirage";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final MirageState STATE = new MirageState();

    /** How often ghosts are re-sent, so they come back after the server resyncs a slot. */
    private static final int REFRESH_INTERVAL_TICKS = 20;
    /** How long a fake dispensed item hangs around before vanishing. */
    private static final int RESULT_LIFETIME_TICKS = 60;
    /** Vanilla schedules the real dispense 4 ticks after the TRIGGERED flag flips. */
    private static final int DISPENSE_DELAY_TICKS = 4;
    /** Grace period for the container screen to actually open after the right-click. */
    private static final int OPEN_GRACE_TICKS = 4;

    private static long tick;

    /** Last observed TRIGGERED state per rigged dispenser, to spot the rising edge. */
    private static final Map<WorldPos, Boolean> lastTriggered = new HashMap<>();
    private static final List<PendingFire> pendingFires = new ArrayList<>();
    private static final List<ExpiringItem> fakeItems = new ArrayList<>();
    /** Players who just right-clicked a rigged dispenser, awaiting their screen to open. */
    private static final Map<UUID, PendingOpen> pendingOpens = new HashMap<>();
    /** Players currently looking into a rigged dispenser. */
    private static final Map<UUID, WorldPos> openRigs = new HashMap<>();

    private record PendingFire(WorldPos pos, long fireAt) {
    }

    private record PendingOpen(WorldPos pos, long deadline) {
    }

    private record ExpiringItem(ItemEntity entity, long removeAt) {
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            STATE.load(server);
            reset();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(STATE::save);

        CommandRegistrationCallback.EVENT.register(MirageCommands::register);
        UseBlockCallback.EVENT.register(Mirage::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(Mirage::onEndTick);

        LOGGER.info("Mirage loaded. Nothing you see here is real.");
    }

    private static void reset() {
        tick = 0;
        lastTriggered.clear();
        pendingFires.clear();
        fakeItems.clear();
        pendingOpens.clear();
        openRigs.clear();
    }

    /** Drops the transient bookkeeping for a dispenser that is no longer rigged. */
    public static void forgetDispenser(WorldPos pos) {
        lastTriggered.remove(pos);
        pendingFires.removeIf(pending -> pending.pos().equals(pos));
        openRigs.values().removeIf(pos::equals);
        pendingOpens.values().removeIf(pending -> pending.pos().equals(pos));
    }

    // ------------------------------------------------------------------ events

    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (world.isClient() || hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
        // Sneak-clicking places a block instead of opening the dispenser.
        if (serverPlayer.isSneaking()) return ActionResult.PASS;

        WorldPos key = WorldPos.of(world, hit.getBlockPos());
        DispenserRig rig = STATE.rigs.get(key);
        if (rig != null && !rig.display.isEmpty() && rig.appliesTo(serverPlayer)) {
            pendingOpens.put(serverPlayer.getUuid(), new PendingOpen(key, tick + OPEN_GRACE_TICKS));
        }

        // Never interfere with the actual interaction.
        return ActionResult.PASS;
    }

    private static void onEndTick(MinecraftServer server) {
        tick++;

        expireFakeItems();
        watchDispensers(server);
        runPendingFires(server);
        applyPendingOpens(server);

        if (tick % REFRESH_INTERVAL_TICKS == 0) {
            refreshEveryone(server);
        }
    }

    private static void expireFakeItems() {
        Iterator<ExpiringItem> iterator = fakeItems.iterator();
        while (iterator.hasNext()) {
            ExpiringItem fake = iterator.next();
            if (fake.entity().isRemoved()) {
                iterator.remove();
            } else if (tick >= fake.removeAt()) {
                fake.entity().discard();
                iterator.remove();
            }
        }
    }

    /**
     * Watches the vanilla TRIGGERED blockstate rather than hooking the dispense itself, so the
     * mod needs no mixins and cannot break the block's real behaviour.
     */
    private static void watchDispensers(MinecraftServer server) {
        for (Map.Entry<WorldPos, DispenserRig> entry : STATE.rigs.entrySet()) {
            if (entry.getValue().result.isEmpty()) continue;

            WorldPos key = entry.getKey();
            ServerWorld world = server.getWorld(key.worldKey());
            if (world == null) continue;

            BlockPos pos = key.pos();
            // Don't force chunks to load just to watch a prank.
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;

            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof DispenserBlock)) {
                lastTriggered.remove(key);
                continue;
            }

            boolean triggered = state.get(DispenserBlock.TRIGGERED);
            Boolean previous = lastTriggered.put(key, triggered);
            // Only the rising edge counts, and never the very first observation.
            if (triggered && previous != null && !previous) {
                pendingFires.add(new PendingFire(key, tick + DISPENSE_DELAY_TICKS));
            }
        }
    }

    private static void runPendingFires(MinecraftServer server) {
        Iterator<PendingFire> iterator = pendingFires.iterator();
        while (iterator.hasNext()) {
            PendingFire pending = iterator.next();
            if (tick < pending.fireAt()) continue;
            iterator.remove();

            DispenserRig rig = STATE.rigs.get(pending.pos());
            if (rig == null || rig.result.isEmpty()) continue;

            ServerWorld world = server.getWorld(pending.pos().worldKey());
            if (world != null) fireFakeResult(world, pending.pos().pos(), rig);
        }
    }

    private static void fireFakeResult(ServerWorld world, BlockPos pos, DispenserRig rig) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof DispenserBlock)) return;

        Direction facing = state.get(DispenserBlock.FACING);
        double x = pos.getX() + 0.5 + facing.getOffsetX() * 0.7;
        double y = pos.getY() + 0.35 + facing.getOffsetY() * 0.7;
        double z = pos.getZ() + 0.5 + facing.getOffsetZ() * 0.7;

        ItemEntity item = new ItemEntity(world, x, y, z, rig.result.copy());
        // Infinite pickup delay also stops vanilla merging it with real drops.
        item.setPickupDelayInfinite();
        item.setInvulnerable(true);

        var random = world.getRandom();
        double spread = 0.06;
        item.setVelocity(
                facing.getOffsetX() * 0.22 + (random.nextDouble() - 0.5) * spread,
                facing.getOffsetY() * 0.22 + 0.10 + (random.nextDouble() - 0.5) * spread,
                facing.getOffsetZ() * 0.22 + (random.nextDouble() - 0.5) * spread);
        item.velocityModified = true;

        world.spawnEntity(item);
        world.playSound(null, pos, SoundEvents.BLOCK_DISPENSER_DISPENSE, SoundCategory.BLOCKS, 1.0F, 1.0F);
        world.spawnParticles(ParticleTypes.SMOKE, x, y, z, 6, 0.05, 0.05, 0.05, 0.02);

        fakeItems.add(new ExpiringItem(item, tick + RESULT_LIFETIME_TICKS));
    }

    private static void applyPendingOpens(MinecraftServer server) {
        Iterator<Map.Entry<UUID, PendingOpen>> iterator = pendingOpens.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingOpen> entry = iterator.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            PendingOpen pending = entry.getValue();

            DispenserRig rig = STATE.rigs.get(pending.pos());
            if (player == null || rig == null) {
                iterator.remove();
                continue;
            }

            if (pushDisplay(player, rig)) {
                openRigs.put(player.getUuid(), pending.pos());
                iterator.remove();
            } else if (tick > pending.deadline()) {
                iterator.remove();
            }
        }
    }

    private static void refreshEveryone(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            WorldPos open = openRigs.get(player.getUuid());
            if (open != null) {
                DispenserRig rig = STATE.rigs.get(open);
                // pushDisplay fails once the container screen is closed.
                if (rig == null || !pushDisplay(player, rig)) openRigs.remove(player.getUuid());
                continue;
            }

            Map<Integer, ItemStack> ghosts = STATE.ghosts.get(player.getUuid());
            if (ghosts != null && !ghosts.isEmpty()) pushGhosts(player, ghosts);
        }
    }

    // ----------------------------------------------------------------- packets

    /** Paints ghost stacks over the player's own inventory screen. */
    public static void pushGhosts(ServerPlayerEntity player, Map<Integer, ItemStack> ghosts) {
        ScreenHandler handler = player.playerScreenHandler;
        // Raw slot indices only mean what we think they mean on the player's own screen.
        if (player.currentScreenHandler != handler) return;

        int revision = handler.nextRevision();
        for (Map.Entry<Integer, ItemStack> entry : ghosts.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= handler.slots.size()) continue;
            player.networkHandler.sendPacket(
                    new ScreenHandlerSlotUpdateS2CPacket(handler.syncId, revision, slot, entry.getValue().copy()));
        }
    }

    /**
     * Paints the rig's fake contents over an open dispenser screen.
     *
     * @return false if the player has no container open, i.e. there is nothing to paint on.
     */
    public static boolean pushDisplay(ServerPlayerEntity player, DispenserRig rig) {
        ScreenHandler handler = player.currentScreenHandler;
        if (handler == player.playerScreenHandler || handler.slots.size() < 9) return false;
        if (!rig.appliesTo(player)) return false;

        int revision = handler.nextRevision();
        for (Map.Entry<Integer, ItemStack> entry : rig.display.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot > 8) continue;
            player.networkHandler.sendPacket(
                    new ScreenHandlerSlotUpdateS2CPacket(handler.syncId, revision, slot, entry.getValue().copy()));
        }
        return true;
    }

    /** Sends the player the truth again. */
    public static void resync(ServerPlayerEntity player) {
        openRigs.remove(player.getUuid());
        pendingOpens.remove(player.getUuid());
        player.currentScreenHandler.syncState();
    }
}
