package gg.embertide.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** A small red light and REC on the game screen while recording, so the player knows without opening the panel. */
final class RecordingLight extends Overlay
{
	static final Color RED = new Color(0xE5, 0x2B, 0x2B);
	private final BooleanSupplier shown;
	/** REC, or CLIPPED for a moment after the clip key. */
	private final Supplier<String> label;

	RecordingLight(BooleanSupplier shown, Supplier<String> label)
	{
		this.shown = shown;
		this.label = label;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!shown.getAsBoolean())
		{
			return null;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics metrics = g.getFontMetrics();
		String text = label.get();
		int dot = 8;
		int height = Math.max(dot, metrics.getAscent()) + 4;
		int textX = dot + 5;
		int width = textX + metrics.stringWidth(text) + 2;
		g.setColor(new Color(0, 0, 0, 140));
		g.fillRoundRect(0, 0, width + 4, height, 6, 6);
		g.setColor(RED);
		g.fillOval(3, (height - dot) / 2, dot, dot);
		int baseline = (height + metrics.getAscent()) / 2 - 1;
		g.setColor(Color.BLACK);
		g.drawString(text, textX + 3, baseline + 1);
		g.setColor(Color.WHITE);
		g.drawString(text, textX + 2, baseline);
		return new Dimension(width + 4, height);
	}
}
