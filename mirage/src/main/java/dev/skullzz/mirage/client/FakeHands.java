package dev.skullzz.mirage.client;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Puts fakes in your hands: placing them, and breaking them again.
 *
 * <p>The client decides what an interaction did before it tells the server anything, so a
 * block can be placed out of an item that is not there and broken again afterwards, sound,
 * cracks and all, with nothing crossing the wire. Every interaction with a fake is cancelled
 * outright, so the server is never told about a click on a slot it thinks is empty.
 *
 * <p>Breaking is driven from the tick rather than the attack: the attack fires once, and a
 * block is broken by holding the button down. So the first hit chooses the block and the
 * tick advances it, at vanilla's own rate for that block and whatever is being swung at it.
 */
public final class FakeHands {
    /** Vanilla's ten stages of cracking, from nothing to gone. */
    private static final int STAGES = 10;
    /** A breaker id of our own, so the cracks are not attributed to a real entity. */
    private static final int BREAKER_ID = Integer.MAX_VALUE - 32768;

    private static BlockPos breaking;
    private static float progress;

    private FakeHands() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register(FakeHands::onUse);
        AttackBlockCallback.EVENT.register(FakeHands::onAttack);
    }

    // ------------------------------------------------------------------ placing

    private static ActionResult onUse(PlayerEntity player, World world, Hand hand,
                                      BlockHitResult hit) {
        // Both hooks fire on the server as well. Being the client's own player is a
        // stronger test of that than asking the world, and the world no longer says.
        if (!(player instanceof ClientPlayerEntity client)) return ActionResult.PASS;
        if (!SelfFakes.enabled()) return ActionResult.PASS;

        // Vanilla gives the block first refusal on a right-click, and only sneaking says
        // otherwise: a button, a lever, a door or a dispenser is being used, not built
        // against. Taking every right-click merely because a fake was in hand took the
        // button that fires the machines with it, and the machines could not be opened
        // either -- the whole mod switched off by holding one of its own items, which is
        // exactly as much of it as was working.
        //
        // So placing lives on the gesture vanilla already reserves for it. Nothing else is
        // ever intercepted, and this cannot come back.
        if (!client.isSneaking()) return ActionResult.PASS;

        int slot = SelfFakes.heldFakeSlot(client, hand);
        if (slot < 0) return ActionResult.PASS;

        // Held a fake and asked to build with it, so nothing about this reaches the server
        // whatever happens next: the slot the server sees is empty, and a click on an
        // empty slot is worse than none.
        FakeSpec spec = SelfFakes.all().get(slot);
        Block block = spec == null ? null : blockOf(spec.item);
        if (block == null) return ActionResult.FAIL;

        BlockPos target = hit.getBlockPos().offset(hit.getSide());
        BlockState state = block.getDefaultState();
        if (!FakeBlocks.place(target, state)) return ActionResult.FAIL;

        SelfFakes.takeOne(slot);
        client.swingHand(hand);
        play(client, state, false);
        return ActionResult.SUCCESS;
    }

    /** The block an item puts down, or null for something that is not one. */
    private static Block blockOf(Item item) {
        return SelfFakes.lookupBlock(Registries.ITEM.getId(item).getPath());
    }

    // ----------------------------------------------------------------- breaking

    private static ActionResult onAttack(PlayerEntity player, World world, Hand hand,
                                         BlockPos pos, Direction direction) {
        if (!(player instanceof ClientPlayerEntity)) return ActionResult.PASS;
        if (FakeBlocks.paintedAt(pos) == null) return ActionResult.PASS;

        // Ours to break, whatever the master switch says about anything else: what is being
        // hit is paint. The server has a different block there, or none, and letting vanilla
        // mine it would send a real break for whatever is really underneath.
        if (!pos.equals(breaking)) {
            breaking = pos.toImmutable();
            progress = 0.0F;
            FakeBlocks.pin(breaking);
        }
        return ActionResult.SUCCESS;
    }

    /**
     * Advances whatever is being broken.
     *
     * <p>At vanilla's own rate: the same call it uses works out how much of a block one tick
     * of swinging takes off, so a fake shulker box gives way as quickly as a real one would
     * to the same tool.
     */
    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || breaking == null) return;

        if (!client.options.attackKey.isPressed() || !aimedAt(client)) {
            stop(world);
            return;
        }

        // What is on the screen there, not merely what we mean to be showing. The sweep has
        // already run this tick and put the pinned block back, so if it is still not paint
        // the illusion is off and the block being hit is the real one.
        BlockState state = FakeBlocks.paintedAt(breaking);
        if (state == null) {
            stop(world);
            return;
        }

        // A box a machine put down is the prize, and the block it is made of was chosen for
        // how it looks rather than for how long it takes: a shulker box gives way in about
        // two ticks to any decent pickaxe, which is not mining it, it is it disappearing. So
        // the answer gets a time of its own -- in creative too, since creative is where the
        // instant break comes from and is the mode it is most wrong in.
        int placed = ClientDispensers.placedBreakTicks(breaking);
        if (placed > 0) {
            progress += 1.0F / placed;
        } else if (player.isCreative()) {
            // Creative takes a block out the instant it is hit, and it does so before the
            // hook our callback answers -- the check for creative sits above it, so no answer
            // we give is ever heard. Left alone, five ticks after the click vanilla mines its
            // own copy and the block simply vanishes: no cracks, no item, and a real break
            // sent for whatever the server has underneath. So the break is finished here
            // first, leaving vanilla nothing of ours at that position to find.
            finish(client, player, world, state);
            return;
        } else {
            progress += state.calcBlockBreakingDelta(player, world, breaking);
        }
        if (progress < 1.0F) {
            world.setBlockBreakingInfo(BREAKER_ID, breaking, (int) (progress * STAGES));
            return;
        }
        finish(client, player, world, state);
    }

    private static boolean aimedAt(MinecraftClient client) {
        return client.crosshairTarget instanceof BlockHitResult hit
                && client.crosshairTarget.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(breaking);
    }

    private static void finish(MinecraftClient client, ClientPlayerEntity player,
                               ClientWorld world, BlockState state) {
        BlockPos pos = breaking;
        world.setBlockBreakingInfo(BREAKER_ID, pos, -1);
        breaking = null;
        progress = 0.0F;
        FakeBlocks.pin(null);

        if (FakeBlocks.broke(pos) == null) return;

        // No particles: the call vanilla uses for them is not where it was, and a wrong
        // guess is a failed build. The cracks and the sound carry it until the real name
        // is confirmed.
        play(player, state, true);

        // Into the bag, the way a broken block goes. Nothing to pick up off the floor,
        // since the floor is the server's and it never knew the block was there.
        Item item = state.getBlock().asItem();
        if (item != Items.AIR) SelfFakes.collect(new FakeSpec(item, 1, ""), player);
    }

    private static void stop(ClientWorld world) {
        if (breaking == null) return;

        world.setBlockBreakingInfo(BREAKER_ID, breaking, -1);
        breaking = null;
        progress = 0.0F;
        FakeBlocks.pin(null);
    }

    /** Vanilla's own volume and pitch for putting a block down or taking one out. */
    private static void play(PlayerEntity player, BlockState state, boolean broken) {
        BlockSoundGroup group = state.getSoundGroup();
        player.playSound(broken ? group.getBreakSound() : group.getPlaceSound(),
                (group.getVolume() + 1.0F) / 2.0F, group.getPitch() * 0.8F);
    }
}
