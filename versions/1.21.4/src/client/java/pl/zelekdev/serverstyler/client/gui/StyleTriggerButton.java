package pl.zelekdev.serverstyler.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The ServerStyler trigger: a real vanilla {@link Button} with the mod's palette
 * icon drawn in the middle (1.21.4 - 1.21.5 window).
 *
 * <p>Extending {@link Button} is what makes this indistinguishable from the Done
 * and Cancel buttons next to it: the background sprite, the disabled state, the
 * hover highlight, the click sound and the focus outline all come from vanilla
 * {@code AbstractButton.renderWidget}. Nothing about the chrome is reimplemented
 * here - only the label is replaced by an image.</p>
 *
 * <h3>Why the texture is 14×14</h3>
 * <p>The artwork is blitted 1:1 into the centre of the button, never stretched. The
 * button itself is 20×20 ({@link StyleEditorPanel#TRIGGER_SIZE}), so the icon sits
 * with a 3 px inset on every side, clear of the button's own border pixels. Because
 * source and destination sizes match, the image stays sharp at every GUI scale.</p>
 *
 * <p>This release predates the pipeline-free {@code blit} overload, so the
 * {@code RenderType} function is supplied directly - a shape that is unique to the
 * 1.21.4 window and therefore safe here.</p>
 */
public final class StyleTriggerButton extends Button {

	/** Edge length of the icon texture; the blit is always 1:1. */
	private static final int TEX_SIZE = 14;
	/** Inset of the icon inside the button, keeping it clear of the border. */
	private static final int ICON_INSET = 3;
	/** Dimming applied over the icon while the button is inactive. */
	private static final int DISABLED_DIM = 0x99000000;

	private static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath("serverstyler", "textures/gui/icon_button.png");

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
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// The vanilla button frame: correct sprite for enabled/disabled/hovered,
		// correct alpha, zero reimplementation.
		super.renderWidget(graphics, mouseX, mouseY, partialTick);

		int size = Math.min(getWidth(), getHeight()) - ICON_INSET * 2;
		if (size <= 0) {
			return;
		}
		int iconLeft = getX() + (getWidth() - size) / 2;
		int iconTop = getY() + (getHeight() - size) / 2;
		int iconRight = iconLeft + size;
		int iconBottom = iconTop + size;

		int tint = this.active ? 0xFFFFFFFF : 0xFFB0B0B0;
		graphics.blit(RenderType::guiTextured, TEXTURE, iconLeft, iconTop, 0.0f, 0.0f,
				size, size, TEX_SIZE, TEX_SIZE, tint);

		if (!this.active) {
			graphics.fill(iconLeft, iconTop, iconRight, iconBottom, DISABLED_DIM);
		}
	}
}