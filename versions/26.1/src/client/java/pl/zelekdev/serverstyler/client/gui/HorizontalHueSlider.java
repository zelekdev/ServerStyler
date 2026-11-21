package pl.zelekdev.serverstyler.client.gui;

import pl.zelekdev.serverstyler.config.ColorUtils;
import pl.zelekdev.serverstyler.i18n.I18n;
import pl.zelekdev.serverstyler.i18n.Keys;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Horizontal continuous hue-spectrum slider (26.1+ extract API).
 *
 * <p>Paints a full 0..360 degree hue ramp left to right, so the colour being
 * chosen is visible directly on the track instead of having to be imagined from
 * a greyscale groove. The caption shows the resulting {@code #RRGGBB}.</p>
 */
public final class HorizontalHueSlider extends AbstractHorizontalSlider {

	private final DoubleConsumer onHueChanged;

	public HorizontalHueSlider(Font font, int x, int y, int width, int height, double hue,
							   DoubleConsumer onHueChanged) {
		super(font, x, y, width, height, Component.literal(I18n.tr(Keys.SLIDER_HUE)), hue);
		this.onHueChanged = onHueChanged;
		updateMessage();
	}

	/** The currently selected hue as a fully-saturated {@code 0xRRGGBB} int. */
	public int getRgb() {
		return ColorUtils.hsvToRgb((float) this.value) & 0xFFFFFF;
	}

	/** The selected hue as a {@code 0..1} fraction. */
	public double getHue() {
		return this.value;
	}

	@Override
	protected void drawTrack(GuiGraphicsExtractor graphics) {
		int left = trackLeft();
		int right = trackRight();
		int top = trackTop();
		int bottom = trackBottom();

		int span = Math.max(1, right - left);
		for (int x = left; x < right; x++) {
			float hue = (float) (x - left) / span;
			int rgb = ColorUtils.hsvToRgb(hue % 1.0f) & 0xFFFFFF;
			graphics.fill(x, top, x + 1, bottom, 0xFF000000 | rgb);
		}
		// A one-pixel dark edge stops the ramp from bleeding into the frame.
		graphics.fill(left, top, right, top + 1, 0x40000000);
		graphics.fill(left, bottom - 1, right, bottom, 0x40000000);
	}

	@Override
	protected void applyValue() {
		if (this.onHueChanged != null) {
			this.onHueChanged.accept(this.value);
		}
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal(String.format(Locale.ROOT, "#%06X", getRgb())));
	}
}