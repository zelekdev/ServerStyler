package pl.zelekdev.serverstyler.client.gui;

import pl.zelekdev.serverstyler.client.ServerStylerClient;
import pl.zelekdev.serverstyler.i18n.I18n;
import pl.zelekdev.serverstyler.i18n.Keys;
import pl.zelekdev.serverstyler.i18n.Lang;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The ModMenu config screen (26.1+ extract/render-state API).
 *
 * <p>Deliberately minimal: the only setting that is not edited in the server
 * editor is the interface language, so this screen is a single cycling button
 * plus two hint lines telling the player where the translation files live and
 * how to change the language without ModMenu.</p>
 *
 * <p>Every label is read through {@link I18n} at build time, and the screen
 * rebuilds its widgets after a language change so the new language is visible
 * immediately.</p>
 *
 * <p>Layout is a single vertical stack measured from the screen centre, so the
 * title, the button and the hints can never overlap at any GUI scale: the rows
 * are {@link #TITLE_OFFSET}, {@link #BUTTON_OFFSET}, {@link #HINT_OFFSET} and
 * {@link #DONE_OFFSET} pixels from the centre line.</p>
 */
public class ServerStylerConfigScreen extends Screen {

	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;

	/** Title baseline, above the language button. */
	private static final int TITLE_OFFSET = -70;
	/** Top of the language button. */
	private static final int BUTTON_OFFSET = -50;
	/** First hint line, below the language button. */
	private static final int HINT_OFFSET = -18;
	/** Line spacing between the two hint lines. */
	private static final int HINT_LINE_HEIGHT = 12;
	/** Top of the Done button, below both hint lines. */
	private static final int DONE_OFFSET = 12;

	private final Screen parent;

	public ServerStylerConfigScreen(Screen parent) {
		super(Component.literal(I18n.tr(Keys.CONFIG_TITLE)));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centreX = this.width / 2;
		int centreY = this.height / 2;
		int buttonX = centreX - BUTTON_WIDTH / 2;

		addRenderableWidget(Button.builder(
						Component.literal(I18n.tr(Keys.CONFIG_LANGUAGE, I18n.lang().displayName())),
						button -> cycleLanguage())
				.bounds(buttonX, centreY + BUTTON_OFFSET, BUTTON_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.literal(I18n.tr(Keys.CONFIG_LANGUAGE_TOOLTIP))))
				.build());

		addRenderableWidget(Button.builder(
						Component.literal(I18n.tr(Keys.CONFIG_DONE)),
						button -> onClose())
				.bounds(buttonX, centreY + DONE_OFFSET, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build());
	}

	/**
	 * Advances to the next language, persists the choice and rebuilds the widgets
	 * so the button label and the title switch immediately.
	 */
	private void cycleLanguage() {
		Lang next = I18n.lang().next();
		ServerStylerClient.applyLanguage(next);
		rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		int centreX = this.width / 2;
		int centreY = this.height / 2;

		graphics.centeredText(this.font, Component.literal(I18n.tr(Keys.CONFIG_TITLE)),
				centreX, centreY + TITLE_OFFSET, 0xFFFFFFFF);

		graphics.centeredText(this.font, Component.literal(I18n.tr(Keys.CONFIG_HINT_FILES)),
				centreX, centreY + HINT_OFFSET, 0xFFA0A0A0);
		graphics.centeredText(this.font, Component.literal(I18n.tr(Keys.CONFIG_HINT_MANUAL)),
				centreX, centreY + HINT_OFFSET + HINT_LINE_HEIGHT, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}
