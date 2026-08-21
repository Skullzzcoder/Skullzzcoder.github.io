package com.skullzz.glaze.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.skullzz.glaze.Glaze;
import com.skullzz.glaze.core.PriceBook;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/** Persists observed prices between sessions. */
public final class PriceBookIO {
	private static final Gson GSON = new GsonBuilder().create();

	private static final Type TYPE =
			new TypeToken<Map<String, List<PriceBook.Sample>>>() { }.getType();

	private PriceBookIO() {
	}

	public static Path file() {
		return ConfigIO.directory().resolve("prices.json");
	}

	public static void load(PriceBook book) {
		Path file = file();

		if (!Files.exists(file)) {
			return;
		}

		try {
			Map<String, List<PriceBook.Sample>> stored =
					GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), TYPE);
			book.loadFrom(stored);
			Glaze.LOG.info("Loaded {} prices across {} items", book.sampleCount(), book.itemCount());
		} catch (IOException | RuntimeException e) {
			// Price history is a convenience, not something worth interrupting play for.
			Glaze.LOG.warn("Could not read {}, starting with an empty price book", file, e);
		}
	}

	public static void save(PriceBook book) {
		Path file = file();

		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling("prices.json.tmp");
			Files.writeString(temp, GSON.toJson(book.snapshot(), TYPE), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Glaze.LOG.error("Could not write {}", file, e);
		}
	}
}
