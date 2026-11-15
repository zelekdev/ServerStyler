package pl.zelekdev.serverstyler;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (loader-independent) entrypoint. The actual feature lives entirely on
 * the client; this initializer only provides shared constants and logging.
 */
public class ServerStyler implements ModInitializer {

	public static final String MOD_ID = "serverstyler";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.debug("ServerStyler common initializer loaded");
	}
}