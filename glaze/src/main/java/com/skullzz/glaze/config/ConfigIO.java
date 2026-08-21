package com.skullzz.glaze.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.skullzz.glaze.Glaze;
import com.skullzz.glaze.core.GlazeConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Loads and saves {@link GlazeConfig}.
 *
 * <p>A config that fails to parse is moved aside rather than deleted, and the mod
 * carries on with defaults. Losing someone's tuned watchlist because of a stray
 * comma would be worse than any feature this mod adds.
 */
public final class ConfigIO {
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private ConfigIO() {
	}

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve(Glaze.MOD_ID);
	}

	public static Path configFile() {
		return directory().resolve("config.json");
	}

	public static GlazeConfig load() {
		Path file = configFile();

		if (!Files.exists(file)) {
			GlazeConfig fresh = new GlazeConfig().sanitised();
			save(fresh);
			return fresh;
		}

		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			GlazeConfig loaded = GSON.fromJson(json, GlazeConfig.class);

			if (loaded == null) {
				throw new JsonSyntaxException("config file was empty");
			}

			return loaded.sanitised();
		} catch (IOException | JsonSyntaxException e) {
			Glaze.LOG.error("Could not read {}, falling back to defaults", file, e);
			quarantine(file);
			return new GlazeConfig().sanitised();
		}
	}

	public static void save(GlazeConfig config) {
		Path file = configFile();

		try {
			Files.createDirectories(file.getParent());
			// Write beside the target and move into place so an interrupted save
			// cannot leave a half-written config behind.
			Path temp = file.resolveSibling("config.json.tmp");
			Files.writeString(temp, GSON.toJson(config), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Glaze.LOG.error("Could not write {}", file, e);
		}
	}

	/** Renames a broken config so the player can recover their settings by hand. */
	private static void quarantine(Path file) {
		try {
			Files.move(file, file.resolveSibling("config.broken.json"),
					StandardCopyOption.REPLACE_EXISTING);
			Glaze.LOG.warn("Kept the unreadable config at config.broken.json");
		} catch (IOException e) {
			Glaze.LOG.error("Could not set aside the broken config", e);
		}
	}
}
