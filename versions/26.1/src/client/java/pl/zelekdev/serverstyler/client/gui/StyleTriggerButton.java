package pl.zelekdev.serverstyler.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The ServerStyler trigger: a real vanilla {@link Button} with the mod's palette
 * icon drawn in the middle (26.1 - 26.3 window).
 *
 * <p>Extending {@link Button} is what makes this indistinguishable from the Done
 * and Cancel buttons next to it: the background sprite, the disabled state, the
 * hover highlight, the click sound and the focus outline all come from vanilla
 * {@code AbstractButton.extractDefaultSprite}. Nothing about the chrome is
 * reimplemented here.</p>
 *
 * <h3>Why {@code extractContents} instead of {@code extractWidgetRenderState}</h3>
 * <p>Vanilla marks {@code extractWidgetRenderState} as {@code final} and delegates
 * to the abstract {@code extractContents}, exactly so that subclasses can change
 * what is drawn <em>inside</em> the standard button frame. Overriding
 * {@code extractContents} therefore keeps the frame and replaces only the label
 * with an image.</p>
 *
 * <h3>Why the blit is pipeline-free</h3>
 * <p>{@code RenderPipeline} moved packages between 26.2 and 26.3, so any call that
 * names that type would have a different JVM descriptor in each release and could
 * not be shared by one jar. The nine-argument
 * {@code blit(Identifier,int,int,int,int,float,float,float,float)} overload has an
 * identical descriptor across the whole 26.x window, so it is used exclusively
 * here. Because it carries no tint parameter, the disabled state is dimmed with a
 * translucent fill instead - which is also stable across the window.</p>
 *
 * <h3>Why the texture is 14×14</h3>
 * <p>The artwork is blitted 1:1 into the centre of the button, never stretched. The
 * button itself is 20×20 ({@link StyleEditorPanel#TRIGGER_SIZE}), so the icon sits
 * with a 3 px inset on every side, clear of the button's own border pixels. Because
 * source and destination sizes match, the image stays sharp at every GUI scale.</p>
 */
public final class StyleTriggerButton extends Button {

	/** Edge length of the icon texture; the blit is always 1:1. */
	private static final int TEX_SIZE = 14;
	/** Inset of the icon inside the button, keeping it clear of the border. */
	private static final int ICON_INSET = 3;
	/** Dimming applied over the icon while the button is inactive. */
	private static final int DISABLED_DIM = 0x99000000;

	private static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath("serverstyler", "textures/gui/icon_button.png");

	public StyleTriggerButton(int x, int y, int size, Component tooltip, Runnable onPress) {
		// Empty label: the vanilla frame is kept, but nothing is drawn in it by the
		// superclass - the icon takes the label's place.
		super(x, y, size, size, Component.empty(),
				button -> {
					if (onPress != null) {
						onPress.run();
					}
				},
				Button.DEFAULT_NARRATION);
		setTooltip(Tooltip.create(tooltip));
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// The vanilla button frame: correct sprite for enabled/disabled/hovered,
		// correct alpha, zero reimplementation.
		extractDefaultSprite(graphics);

		int size = Math.min(getWidth(), getHeight()) - ICON_INSET * 2;
		if (size <= 0) {
			return;
		}
		int iconLeft = getX() + (getWidth() - size) / 2;
		int iconTop = getY() + (getHeight() - size) / 2;
		int iconRight = iconLeft + size;
		int iconBottom = iconTop + size;

		// Pipeline-free and tint-free: identical descriptor in 26.1, 26.2 and 26.3.
		graphics.blit(TEXTURE, iconLeft, iconTop, iconRight, iconBottom,
				0.0f, 1.0f, 0.0f, 1.0f);

		if (!this.active) {
			graphics.fill(iconLeft, iconTop, iconRight, iconBottom, DISABLED_DIM);
		}
	}
}