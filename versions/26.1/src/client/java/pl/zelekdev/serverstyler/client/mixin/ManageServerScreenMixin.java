package pl.zelekdev.serverstyler.client.mixin;

import pl.zelekdev.serverstyler.client.ServerStylerClient;
import pl.zelekdev.serverstyler.client.gui.PresetPanel;
import pl.zelekdev.serverstyler.client.gui.StyleEditorPanel;
import pl.zelekdev.serverstyler.client.gui.StyleTriggerButton;
import pl.zelekdev.serverstyler.config.ConfigManager;
import pl.zelekdev.serverstyler.config.PresetStore;
import pl.zelekdev.serverstyler.config.ServerStyle;
import pl.zelekdev.serverstyler.config.ServerStyleConfig;
import pl.zelekdev.serverstyler.i18n.I18n;
import pl.zelekdev.serverstyler.i18n.Keys;
import pl.zelekdev.serverstyler.render.TextStylingEngine;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects the ServerStyler trigger button + floating {@link StyleEditorPanel}
 * into {@code ManageServerScreen} (the 26.1+ replacement for
 * {@code EditServerScreen}), wires up the live WYSIWYG preview and persists the
 * edited style when the user accepts the screen.
 *
 * <p>As in the 1.21.4 mixin, persistence is hooked on {@code onAdd} (the
 * Done/Add path), <em>not</em> {@code onClose} (the cancel path). Vanilla's
 * {@code onAdd} fires the {@code BooleanConsumer} callback and swaps screens
 * without ever calling {@code onClose}, so an {@code onClose} hook would only
 * ever persist on cancel.</p>
 *
 * <h3>Input hardening</h3>
 * <p>The name field is capped at {@link ServerStyleConfig#MAX_NAME_LENGTH} and its
 * contents are sanitised in {@code onAdd} <em>before</em> vanilla copies the value
 * into {@link ServerData}. The vanilla responders are deliberately left untouched:
 * they only call {@code updateAddButtonStatus()}, and replacing them would break
 * the Done button's enable/disable logic.</p>
 */
@Mixin(ManageServerScreen.class)
public abstract class ManageServerScreenMixin {

	@Shadow
	private EditBox nameEdit;

	@Shadow
	private EditBox ipEdit;

	@Shadow
	private ServerData serverData;

	@Unique
	private StyleEditorPanel serverstyler$panel;

	@Unique
	private PresetPanel serverstyler$presets;

	@Unique
	private StyleTriggerButton serverstyler$trigger;

	@Unique
	private StyleEditorPanel.Anchor serverstyler$anchor;

	/** The address this screen was opened with, captured in {@code init}. */
	@Unique
	private String serverstyler$originalIp;

	/**
	 * Latches {@code true} once the user has edited the style in <em>any</em>
	 * incarnation of the panel. {@code init()} rebuilds the panel on a resize,
	 * which resets its internal {@code modified} flag; this preserves the intent
	 * to save.
	 */
	@Unique
	private boolean serverstyler$hadEdits;

	@Inject(method = "init", at = @At("TAIL"))
	private void serverstyler$init(CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		ScreenInvoker invoker = (ScreenInvoker) (Object) this;

		this.serverstyler$originalIp = this.serverData == null ? null : this.serverData.ip;

		ServerStyle working;
		boolean wasVisible;
		if (this.serverstyler$panel != null) {
			working = this.serverstyler$panel.style();
			wasVisible = this.serverstyler$panel.isVisible();
			// Preserve the edit latch before the old panel is discarded.
			this.serverstyler$hadEdits |= this.serverstyler$panel.isModified();
		} else {
			ConfigManager config = ServerStylerClient.config();
			ServerStyle existing = config == null || this.serverData == null
					? null
					: config.getRaw(this.serverData.ip);
			working = existing != null ? existing.copy() : new ServerStyle();
			wasVisible = false;
		}

		this.serverstyler$anchor = serverstyler$makeAnchor();
		StyleEditorPanel panel = new StyleEditorPanel(working, self.getFont(), this.serverstyler$anchor);

		// Register the panel FIRST so its backdrop draws beneath its children, then
		// let it add its own children through Minecraft's normal widget pipeline.
		invoker.serverstyler$addRenderableWidget(panel);
		panel.addChildren(invoker::serverstyler$addRenderableWidget);
		this.serverstyler$panel = panel;
		panel.setVisible(wasVisible);

		// The preset shelf hangs directly below the editor panel and shares its
		// visibility, so one trigger button opens both.
		PresetStore presets = ServerStylerClient.presets();
		if (presets != null) {
			PresetPanel shelf = new PresetPanel(working, self.getFont(), presets,
					this.serverstyler$anchor, panel::onStyleReplaced);
			invoker.serverstyler$addRenderableWidget(shelf);
			shelf.addChildren(invoker::serverstyler$addRenderableWidget);
			this.serverstyler$presets = shelf;
			shelf.setVisible(wasVisible);
		}

		this.serverstyler$trigger = new StyleTriggerButton(
				0, 0, StyleEditorPanel.TRIGGER_SIZE,
				Component.literal(I18n.tr(Keys.TRIGGER_TOOLTIP)),
				() -> serverstyler$togglePanels());
		invoker.serverstyler$addRenderableWidget(this.serverstyler$trigger);

		// Anchor everything to the name field for the current screen size.
		serverstyler$reposition();

		// Hard length guards. Vanilla truncates pasted input to this limit, so an
		// over-long paste can never reach the config file or the renderer.
		this.nameEdit.setMaxLength(ServerStyleConfig.MAX_NAME_LENGTH);
		if (this.ipEdit != null) {
			this.ipEdit.setMaxLength(ServerStyleConfig.MAX_ADDRESS_LENGTH);
		}

		// Live WYSIWYG via the EditBox text formatter.
		this.nameEdit.addFormatter(this::serverstyler$formatName);
	}

	/**
	 * Opens or closes the editor panel and the preset shelf together.
	 *
	 * <p>They are one visual unit: the shelf is meaningless without the editor, and
	 * leaving it on screen after the editor closes would look like a stray window.</p>
	 */
	@Unique
	private void serverstyler$togglePanels() {
		StyleEditorPanel panel = this.serverstyler$panel;
		if (panel == null) {
			return;
		}
		boolean show = !panel.isVisible();
		panel.setVisible(show);
		if (this.serverstyler$presets != null) {
			this.serverstyler$presets.setVisible(show);
		}
	}

	/**
	 * Repositions the trigger button and the panel using GUI-scaled screen
	 * coordinates. Invoked from {@code init} and from every extract pass, so the
	 * layout self-heals after a GUI-scale or window-size change.
	 */
	@Unique
	private void serverstyler$reposition() {
		StyleEditorPanel panel = this.serverstyler$panel;
		StyleEditorPanel.Anchor anchor = this.serverstyler$anchor;
		if (panel == null || anchor == null) {
			return;
		}
		panel.relayout();

		// The preset column prefers the space to the RIGHT of the editor panel and
		// only falls back to stacking below it. It never goes to the left, because
		// that used to cover the name field. If neither spot fits it hides itself.
		if (this.serverstyler$presets != null) {
			PresetPanel presets = this.serverstyler$presets;
			if (!presets.tryPlaceRight(panel.getX(), panel.getY(), panel.height())) {
				// StyleEditorPanel.relayout() already lifts the editor to the top
				// margin in compact mode when the column would not fit below it, so
				// this only has to decide between stacking and hiding.
				int stackHeight = presets.heightForStack(panel.getY(), panel.height());
				if (stackHeight > 0) {
					presets.placeBelow(panel.getX(), panel.getY(), panel.height(), stackHeight);
				} else {
					presets.hideForSpace();
				}
			}
		}

		if (this.serverstyler$trigger != null) {
			int screenWidth = anchor.screenWidth();
			int screenHeight = anchor.screenHeight();
			boolean valid = screenWidth > 0 && screenHeight > 0 && anchor.fieldRight() > 0;
			this.serverstyler$trigger.visible = valid;
			this.serverstyler$trigger.active = valid;
			if (valid) {
				this.serverstyler$trigger.setX(StyleEditorPanel.triggerX(anchor.fieldRight(), screenWidth));
				this.serverstyler$trigger.setY(StyleEditorPanel.triggerY(anchor.fieldTop(), screenHeight));
				this.serverstyler$trigger.setWidth(StyleEditorPanel.TRIGGER_SIZE);
				this.serverstyler$trigger.setHeight(StyleEditorPanel.TRIGGER_SIZE);
			}
		}
	}

	/**
	 * Builds a live anchor that reads the current name-field bounds and the
	 * GUI-scaled screen size on every call, so the panel snaps to the field even
	 * after a window resize or GUI-scale change.
	 */
	@Unique
	private StyleEditorPanel.Anchor serverstyler$makeAnchor() {
		return new StyleEditorPanel.Anchor() {
			@Override
			public int fieldLeft() {
				EditBox edit = ManageServerScreenMixin.this.nameEdit;
				return edit == null ? 0 : edit.getX();
			}

			@Override
			public int fieldRight() {
				EditBox edit = ManageServerScreenMixin.this.nameEdit;
				return edit == null ? 0 : edit.getX() + edit.getWidth();
			}

			@Override
			public int fieldTop() {
				EditBox edit = ManageServerScreenMixin.this.nameEdit;
				return edit == null ? 0 : edit.getY();
			}

			@Override
			public int screenWidth() {
				// GUI-scaled width only - no window/frame metrics, no raw pixels.
				return ((Screen) (Object) ManageServerScreenMixin.this).width;
			}

			@Override
			public int screenHeight() {
				return ((Screen) (Object) ManageServerScreenMixin.this).height;
			}
		};
	}

	/**
	 * Live preview formatter for the server-name field.
	 *
	 * <p>{@code EditBox} may split the visible value into two slices around the
	 * caret and calls this once per slice, passing the slice's absolute offset as
	 * {@code firstCharacterIndex}. We colour against the <b>full</b> field value so
	 * the gradient is continuous across both slices.</p>
	 *
	 * <p>Alignment padding is emitted on the first slice only, so the split at the
	 * caret still lines up. It applies <em>while focused too</em>: vanilla draws the
	 * caret at the X returned by {@code drawString(Font, FormattedCharSequence, ...)},
	 * which already includes the prefix we emit, so the caret follows the aligned
	 * glyphs correctly. Gating this on {@code !isFocused()} (as an earlier revision
	 * did) made a centred/right name snap back to the left the moment the field took
	 * focus &mdash; which is exactly why the toggle looked "stuck" while the L/C/R
	 * button stayed highlighted.</p>
	 */
	@Unique
	private FormattedCharSequence serverstyler$formatName(String text, int firstCharacterIndex) {
		StyleEditorPanel panel = this.serverstyler$panel;
		if (panel == null || !panel.style().isEnabled()) {
			return FormattedCharSequence.forward(text, Style.EMPTY);
		}
		ServerStyle style = panel.style();
		String fullValue = this.nameEdit != null ? this.nameEdit.getValue() : text;

		// Fit the visible text to the field so the preview can never overflow it,
		// exactly like the multiplayer list does. Recomputed per call, so a window
		// resize or GUI-scale change takes effect immediately.
		String fittedFull = serverstyler$fittedFullValue(fullValue);
		String shown = serverstyler$fitToField(text, firstCharacterIndex, fullValue, fittedFull);

		// Mini Caps after slicing: toMiniCaps maps one code point to one code point
		// (a small capital plus any diacritic fold), so applying it per slice keeps
		// both halves aligned with the caret offsets EditBox supplies.
		shown = TextStylingEngine.applyMiniCaps(shown, style);

		FormattedCharSequence styled =
				TextStylingEngine.buildFormatted(shown, style, firstCharacterIndex, fullValue.length());

		if (style.isAlignLeft() || firstCharacterIndex != 0) {
			return styled;
		}
		// Align against the WHOLE fitted value, not this slice: EditBox splits the
		// text at the caret and formats each half separately, so measuring `shown`
		// made the padding collapse as soon as the caret moved into the name (the
		// glyphs then jumped left/right). A stable offset keeps the name put.
		int pad = serverstyler$alignmentPadding(
				TextStylingEngine.applyMiniCaps(fittedFull, style), style);
		if (pad <= 0) {
			return styled;
		}
		FormattedCharSequence prefix =
				FormattedCharSequence.forward(" ".repeat(pad), Style.EMPTY);
		return FormattedCharSequence.composite(prefix, styled);
	}

	/**
	 * The sanitised value of the name field, trimmed to the field's live inner
	 * width. Shared by the caret-slicing and the alignment code so both agree on
	 * exactly which glyphs are on screen.
	 */
	@Unique
	private String serverstyler$fittedFullValue(String fullValue) {
		String clean = TextStylingEngine.prepare(fullValue);
		if (this.nameEdit == null) {
			return clean;
		}
		Font font = ((Screen) (Object) this).getFont();
		int innerWidth = this.nameEdit.getInnerWidth();
		if (innerWidth <= 0) {
			return clean;
		}
		return TextStylingEngine.trimToWidth(clean, innerWidth, font::width);
	}

	/**
	 * Clamps the rendered preview to the field's inner width.
	 *
	 * <p>{@code EditBox} may hand us a slice (prefix before the caret, suffix after
	 * it), and the slices together must fit. When the full value overflows we
	 * re-slice the already-trimmed string at the same absolute offset so the caret
	 * split still lines up. Because {@code getInnerWidth()} is read fresh on every
	 * call, this adapts to resizes and GUI-scale changes with no cached
	 * constants.</p>
	 */
	@Unique
	private String serverstyler$fitToField(String slice, int firstCharacterIndex, String fullValue,
			String fittedFull) {
		if (this.nameEdit == null) {
			return slice;
		}
		if (fittedFull.equals(TextStylingEngine.prepare(fullValue))) {
			// Nothing was dropped, so the raw slice is still caret-accurate.
			return slice;
		}

		// The value had to be shortened: re-slice the trimmed string at the same
		// absolute offset so the caret split still lines up.
		int start = Math.max(0, Math.min(firstCharacterIndex, fittedFull.length()));
		int end = Math.min(fittedFull.length(), start + slice.length());
		return start >= end ? fittedFull.substring(Math.min(start, fittedFull.length()))
				: fittedFull.substring(start, end);
	}

	/**
	 * Number of space characters needed to align the styled value inside the name
	 * field, in whole glyph cells.
	 *
	 * <p>Takes the <em>whole</em> fitted value rather than a caret slice, so the
	 * offset stays constant while the caret moves and the text does not drift.</p>
	 */
	@Unique
	private int serverstyler$alignmentPadding(String fullValue, ServerStyle style) {
		if (this.nameEdit == null) {
			return 0;
		}
		Font font = ((Screen) (Object) this).getFont();
		MutableComponent built = TextStylingEngine.buildComponent(fullValue, style);
		// Font.width measures with Style.EMPTY, so bold text is under-reported by
		// 1 px per glyph (GlyphInfo.getBoldOffset()). Add it back or a bold name
		// overhangs the right edge by exactly textLength pixels.
		int textWidth = font.width(built)
				+ (style.isBold() ? fullValue.codePointCount(0, fullValue.length()) : 0);
		int innerWidth = this.nameEdit.getInnerWidth();
		int padPixels = TextStylingEngine.alignedPadding(innerWidth, textWidth, style.getAlignment());
		if (padPixels <= 0) {
			return 0;
		}
		int spaceWidth = Math.max(1, font.width(" "));
		return padPixels / spaceWidth;
	}

	/**
	 * Persists the working style when the user accepts the screen (Done / Add).
	 * Runs at {@code HEAD} of the private {@code onAdd()}, before vanilla copies the
	 * fields into {@link ServerData} and fires the callback.
	 */
	@Inject(method = "onAdd", at = @At("HEAD"))
	private void serverstyler$persistOnDone(CallbackInfo ci) {
		// Sanitise the name field in place first: vanilla's own body (which runs
		// after this HEAD injection) copies it verbatim into ServerData.
		if (this.nameEdit != null) {
			String current = this.nameEdit.getValue();
			String clean = ServerStyleConfig.sanitiseName(current);
			if (!clean.equals(current)) {
				this.nameEdit.setValue(clean);
			}
		}

		ConfigManager config = ServerStylerClient.config();
		StyleEditorPanel panel = this.serverstyler$panel;
		if (config == null || panel == null) {
			return;
		}

		String oldIp = this.serverstyler$originalIp != null
				? this.serverstyler$originalIp
				: (this.serverData != null ? this.serverData.ip : null);
		String newIp = this.ipEdit != null ? this.ipEdit.getValue() : oldIp;

		String oldKey = ServerStyleConfig.normaliseIp(oldIp);
		String newKey = ServerStyleConfig.normaliseIp(newIp);
		boolean ipChanged = !oldKey.isEmpty() && !oldKey.equals(newKey);
		ServerStyle existing = config.getRaw(oldIp);

		boolean edited = this.serverstyler$hadEdits || panel.isModified();
		if (!edited && !(ipChanged && existing != null)) {
			return;
		}

		config.saveForServer(oldIp, newIp, panel.style());
		config.saveAsync();
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void serverstyler$repositionBeforeExtract(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Re-anchor to the name field before the widget list is extracted, so a
		// window resize or GUI-scale change takes effect on the very next frame.
		serverstyler$reposition();
	}
}