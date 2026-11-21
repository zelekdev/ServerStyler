package pl.zelekdev.serverstyler.client;

import pl.zelekdev.serverstyler.client.gui.ServerStylerConfigScreen;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Optional ModMenu hook.
 *
 * <p>ModMenu is a soft dependency: this class is only ever loaded by ModMenu
 * itself (through the {@code modmenu} entrypoint), so a player without ModMenu
 * never touches it and the mod loads normally. The dependency is
 * {@code modCompileOnly}, so nothing from ModMenu is bundled into the jar.</p>
 *
 * <p>The ModMenu API is identical across every Minecraft version this mod
 * targets (13.0.4 / 15.0.2 / 16.0.1 / 18.0.2), so this single source file is
 * shared by all four build targets.</p>
 */
public final class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ServerStylerConfigScreen::new;
	}
}
