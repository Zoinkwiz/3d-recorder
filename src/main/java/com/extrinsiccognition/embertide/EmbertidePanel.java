package com.extrinsiccognition.embertide;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.text.DefaultCaret;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.LinkBrowser;

public final class EmbertidePanel extends PluginPanel
{
	static final String DISCORD_URL = "https://discord.gg/XbGqAEubSR";
	private final JLabel headline = new JLabel("Not recording");
	private final JTextArea status = text("");
	private final JPanel files = new JPanel();
	private final JButton toggle = new JButton("Stop recording");
	private final JButton folder = new JButton("Show my recordings");
	private final JButton retry = new JButton("Retry saving");
	private final JButton discard = new JButton("Discard unsaved recording");
	private final Timer refresh;
	private final MaterialTabGroup tabs;
	private final MaterialTab watchTab;
	// GridLayout keeps room for hidden buttons, so Retry and Discard are added only while they apply.
	private final JPanel actions = new JPanel(new GridLayout(0, 1, 0, 6));
	private Boolean recoverShown;
	private String drawn = "";
	private final EmbertidePlugin plugin;

	EmbertidePanel(EmbertidePlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setOpaque(false);
		headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize2D() + 2f));
		headline.setAlignmentX(LEFT_ALIGNMENT);
		headline.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		top.add(headline);
		JTextArea where = text("Play as normal. When you're done, open your session in the Embertide app or at");
		top.add(where);
		top.add(studioLink("embertide.gg/studio"));
		JPanel record = new JPanel(new BorderLayout(0, 8));
		record.setOpaque(false);
		record.add(top, BorderLayout.NORTH);

		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		status.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
		middle.add(status);
		files.setLayout(new BoxLayout(files, BoxLayout.Y_AXIS));
		files.setOpaque(false);
		files.setAlignmentX(LEFT_ALIGNMENT);
		middle.add(files);
		middle.add(Box.createVerticalGlue());
		record.add(middle, BorderLayout.CENTER);

		actions.setOpaque(false);
		toggle.addActionListener(e -> plugin.toggleRecording());
		folder.addActionListener(e -> {
			JTextArea path = new JTextArea(plugin.folderPath());
			path.setEditable(false);
			path.setLineWrap(true);
			path.setColumns(40);
			path.setRows(3);
			path.selectAll();
			JOptionPane.showMessageDialog(this, path, "Your recordings are here", JOptionPane.INFORMATION_MESSAGE);
		});
		folder.setToolTipText("Shows where your recordings are saved, so you can find them or drag one into Studio.");
		retry.addActionListener(e -> plugin.retrySave());
		discard.addActionListener(e -> {
			if (JOptionPane.showConfirmDialog(this,
				"Throw away the recording that failed to save? You can't get it back.", "Discard recording",
				JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
			{
				plugin.discardSave();
			}
		});
		showRecover(false);
		record.add(actions, BorderLayout.SOUTH);

		JPanel display = new JPanel(new BorderLayout());
		display.setOpaque(false);
		tabs = new MaterialTabGroup(display);
		tabs.setLayout(new GridLayout(1, 2));
		MaterialTab recordTab = new MaterialTab("Record", tabs, record);
		watchTab = new MaterialTab("Watch", tabs, plugin.viewer().panel());
		tabs.addTab(recordTab);
		tabs.addTab(watchTab);
		tabs.select(recordTab);
		add(tabs, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
		JLabel discord = link("Join the Embertide Discord", () -> LinkBrowser.browse(DISCORD_URL));
		discord.setToolTipText("Opens our Discord invite in your browser: questions, bugs and what you made.");
		add(discord, BorderLayout.SOUTH);

		refresh = new Timer(500, e -> render());
		refresh.setInitialDelay(0);
		render();
	}

	private void render()
	{
		EmbertidePlugin.PanelState state = plugin.panelState();
		boolean live = state.headline.startsWith("Recording");
		String shown = (live ? "● " : "") + state.headline;
		if (!shown.equals(headline.getText()))
		{
			headline.setText(shown);
			headline.setForeground(live ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		}
		if (!state.text.equals(status.getText()))
		{
			status.setText(state.text);
		}
		toggle.setText(state.recording ? "Stop recording" : "Start recording");
		toggle.setEnabled(state.canToggle);
		showRecover(plugin.canRecover());
		drawFiles(state.current, state.saved);
	}

	private void drawFiles(String current, List<EmbertidePlugin.SavedFile> saved)
	{
		StringBuilder key = new StringBuilder(current);
		for (EmbertidePlugin.SavedFile file : saved)
		{
			key.append('|').append(file.name);
		}
		if (key.toString().equals(drawn))
		{
			return;
		}
		drawn = key.toString();
		files.removeAll();
		if (current.isEmpty() && saved.isEmpty())
		{
			files.revalidate();
			files.repaint();
			return;
		}
		if (current.isEmpty())
		{
			// After a stop, the next step comes before the list.
			JLabel next = new JLabel("Next");
			next.setFont(next.getFont().deriveFont(Font.BOLD));
			next.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			next.setAlignmentX(LEFT_ALIGNMENT);
			files.add(next);
			JTextArea how = text("Watch a part below in your house, or open Studio and drag your recording in.");
			how.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
			files.add(how);
			files.add(studioLink("Open in Studio"));
			files.add(Box.createVerticalStrut(10));
		}
		JLabel heading = new JLabel("This session");
		heading.setFont(heading.getFont().deriveFont(Font.BOLD));
		heading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		heading.setAlignmentX(LEFT_ALIGNMENT);
		files.add(heading);
		files.add(Box.createVerticalStrut(4));
		if (!current.isEmpty())
		{
			files.add(row(current, "recording now", null));
		}
		for (int i = saved.size() - 1; i >= 0; i--)
		{
			EmbertidePlugin.SavedFile file = saved.get(i);
			int seconds = (int) file.seconds;
			files.add(row(file.name, String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60), () -> watch(file.name)));
		}
		files.revalidate();
		files.repaint();
	}

	private void watch(String name)
	{
		plugin.viewer().load(name);
		// The group swaps the content; MaterialTab.select() alone only restyles the tab.
		tabs.select(watchTab);
	}

	private static JPanel row(String name, String note, Runnable watch)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
		JLabel file = new JLabel(name);
		file.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		file.setToolTipText(name);
		JLabel detail = new JLabel(note);
		detail.setFont(detail.getFont().deriveFont(detail.getFont().getSize2D() - 1f));
		detail.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		row.add(file);
		row.add(detail);
		if (watch != null)
		{
			row.add(link("Watch in your house", watch));
		}
		return row;
	}

	private static JLabel studioLink(String label)
	{
		JLabel studio = link(label, () -> LinkBrowser.browse(MccrFileRecorder.STUDIO_URL));
		studio.setToolTipText("Opens Studio in your browser. Drag a recording from your recordings folder into it.");
		return studio;
	}

	private static JLabel link(String label, Runnable action)
	{
		JLabel link = new JLabel("<html><u>" + label + "</u></html>");
		link.setForeground(ColorScheme.BRAND_ORANGE);
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.setAlignmentX(LEFT_ALIGNMENT);
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}
		});
		return link;
	}

	private void showRecover(boolean recover)
	{
		if (Boolean.valueOf(recover).equals(recoverShown))
		{
			return;
		}
		recoverShown = recover;
		actions.removeAll();
		actions.add(toggle);
		if (recover)
		{
			actions.add(retry);
			actions.add(discard);
		}
		actions.add(folder);
		actions.revalidate();
	}

	/** Read-only text whose caret never scrolls the sidebar when the text changes. */
	private static JTextArea text(String content)
	{
		JTextArea area = new JTextArea(content);
		area.setEditable(false);
		area.setFocusable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		// The look and feel's text margin would indent text past the buttons and links.
		area.setBorder(BorderFactory.createEmptyBorder());
		area.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		area.setAlignmentX(LEFT_ALIGNMENT);
		((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		return area;
	}

	@Override
	public void onActivate()
	{
		refresh.start();
	}

	@Override
	public void onDeactivate()
	{
		refresh.stop();
	}

	void dispose()
	{
		refresh.stop();
	}
}
