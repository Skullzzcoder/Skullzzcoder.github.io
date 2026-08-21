package com.skullzz.glaze.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the container menu's origin and size.
 *
 * <p>Slot coordinates are relative to the menu's top-left corner, and those fields
 * are protected. Drawing anything aligned to a slot from outside the screen class
 * needs them, so this is the mod's only mixin - an accessor, with no injected
 * behaviour and nothing to conflict with other mods over.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
	@Accessor("x")
	int glaze$getX();

	@Accessor("y")
	int glaze$getY();

	@Accessor("backgroundWidth")
	int glaze$getBackgroundWidth();

	@Accessor("backgroundHeight")
	int glaze$getBackgroundHeight();
}
