package gg.embertide.runelite;

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
	private final JPanel choice = new JPanel();
	private final JButton toggle = new JButton("Stop recording");
	private final JButton clip = new JButton("Save last 20s");
	private final JTextArea clipsLine = text("");
	/** Parts shown before "Show N earlier"; every part once that is pressed. */
	static final int PARTS_SHOWN = 3;
	private boolean allParts;
	private final JButton retry = new JButton("Retry saving");
	private final JButton discard = new JButton("Discard unsaved recording");
	private final Timer refresh;
	private final MaterialTabGroup tabs;
	private final MaterialTab watchTab;
	// GridLayout keeps room for hidden buttons, so Retry and Discard are added only while they apply.
	private final JPanel actions = new JPanel(new GridLayout(0, 1, 0, 6));
	private final JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
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
		headline.setToolTipText("Other players are recorded as unnamed figures.");
		top.add(headline);
		status.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		top.add(status);
		buttons.setOpaque(false);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		toggle.addActionListener(e -> plugin.toggleRecording());
		clip.addActionListener(e -> plugin.clipThat());
		buttons.add(toggle);
		buttons.add(clip);
		top.add(buttons);
		clipsLine.setAlignmentX(LEFT_ALIGNMENT);
		clipsLine.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		top.add(clipsLine);
		JPanel record = new JPanel(new BorderLayout(0, 8));
		record.setOpaque(false);
		record.add(top, BorderLayout.NORTH);

		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		choice.setLayout(new BoxLayout(choice, BoxLayout.Y_AXIS));
		choice.setOpaque(false);
		choice.setAlignmentX(LEFT_ALIGNMENT);
		choice.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
		JLabel ask = new JLabel("Record every time you log in?");
		ask.setFont(ask.getFont().deriveFont(Font.BOLD));
		ask.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		ask.setAlignmentX(LEFT_ALIGNMENT);
		choice.add(ask);
		JPanel answers = new JPanel(new GridLayout(0, 1, 0, 4));
		answers.setOpaque(false);
		answers.setAlignmentX(LEFT_ALIGNMENT);
		answers.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		JButton always = new JButton("Every time I log in");
		always.addActionListener(e -> plugin.chooseRecordOnLogin(true));
		JButton byHand = new JButton("Only when I press Start");
		byHand.addActionListener(e -> plugin.chooseRecordOnLogin(false));
		answers.add(always);
		answers.add(byHand);
		choice.add(answers);
		choice.add(text("You can change this later in the plugin's settings, under Record when you log in."));
		choice.setVisible(false);
		middle.add(choice);
		middle.add(Box.createVerticalStrut(10));
		files.setLayout(new BoxLayout(files, BoxLayout.Y_AXIS));
		files.setOpaque(false);
		files.setAlignmentX(LEFT_ALIGNMENT);
		middle.add(files);
		middle.add(Box.createVerticalGlue());
		record.add(middle, BorderLayout.CENTER);

		actions.setOpaque(false);
		JLabel folder = link("Show my recordings", () -> {
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
		JPanel foot = new JPanel();
		foot.setLayout(new BoxLayout(foot, BoxLayout.Y_AXIS));
		foot.setOpaque(false);
		foot.add(actions);
		foot.add(Box.createVerticalStrut(8));
		folder.setAlignmentX(LEFT_ALIGNMENT);
		foot.add(folder);
		foot.add(studioLink("Open Studio"));
		record.add(foot, BorderLayout.SOUTH);

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
		if (choice.isVisible() != state.askLoginChoice)
		{
			choice.setVisible(state.askLoginChoice);
		}
		toggle.setText(state.recording ? "Stop recording" : "Start recording");
		toggle.setEnabled(state.canToggle);
		clip.setEnabled(state.canClip);
		String label = "Save last " + state.clipBefore + "s";
		if (!label.equals(clip.getText()))
		{
			clip.setText(label);
			clip.setToolTipText("Saves a recording of the last " + state.clipBefore + " seconds and the " + state.clipAfter
				+ " after you press it, whether you are recording or not. Your Clip that hotkey does the same.");
		}
		if (!state.clipsLine.equals(clipsLine.getText()))
		{
			clipsLine.setText(state.clipsLine);
		}
		status.setVisible(!state.text.isEmpty());
		showRecover(plugin.canRecover());
		drawFiles(state.current, state.saved, state.clips);
	}

	private void drawFiles(String current, List<EmbertidePlugin.SavedFile> saved, List<EmbertidePlugin.SavedFile> clips)
	{
		StringBuilder key = new StringBuilder(current).append('|').append(allParts);
		for (EmbertidePlugin.SavedFile file : saved) { key.append('|').append(file.name); }
		for (EmbertidePlugin.SavedFile file : clips) { key.append("|c").append(file.name); }
		if (key.toString().equals(drawn))
		{
			return;
		}
		drawn = key.toString();
		files.removeAll();
		if (!clips.isEmpty())
		{
			files.add(heading("Clips"));
			for (int i = clips.size() - 1; i >= 0; i--)
			{
				EmbertidePlugin.SavedFile file = clips.get(i);
				files.add(row(file.title, clock(file.seconds), () -> watch(file.name)));
			}
			files.add(Box.createVerticalStrut(10));
		}
		if (!current.isEmpty() || !saved.isEmpty())
		{
			files.add(heading("This session"));
			if (!current.isEmpty())
			{
				files.add(row(partName(current, "Now"), "recording", null));
			}
			int shown = 0;
			for (int i = saved.size() - 1; i >= 0 && (allParts || shown < PARTS_SHOWN); i--, shown++)
			{
				EmbertidePlugin.SavedFile file = saved.get(i);
				files.add(row(partName(file.name, "Part"), clock(file.seconds), () -> watch(file.name)));
			}
			int hidden = saved.size() - shown;
			if (hidden > 0)
			{
				files.add(link("Show " + hidden + " earlier", () -> { allParts = true; render(); }));
			}
		}
		files.revalidate();
		files.repaint();
	}

	/** A part by the time it began, from its file name ("osrs-20261031-2131-…" reads "From 21:31"). */
	static String partName(String file, String otherwise)
	{
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("^osrs-\\d{8}-(\\d{2})(\\d{2})").matcher(file);
		return m.find() ? "From " + m.group(1) + ":" + m.group(2) : otherwise;
	}

	static String clock(double seconds)
	{
		int s = (int) Math.max(0, seconds);
		return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	private static JLabel heading(String words)
	{
		JLabel heading = new JLabel(words);
		heading.setFont(heading.getFont().deriveFont(Font.BOLD));
		heading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		heading.setAlignmentX(LEFT_ALIGNMENT);
		heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		return heading;
	}

	private void watch(String name)
	{
		plugin.viewer().load(name);
		// The group swaps the content; MaterialTab.select() alone only restyles the tab.
		tabs.select(watchTab);
	}

	private static JPanel row(String name, String note, Runnable watch)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(1, 0, 3, 0));
		JLabel label = new JLabel(name);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setToolTipText(name);
		row.add(label, BorderLayout.CENTER);
		JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
		right.setOpaque(false);
		JLabel detail = new JLabel(note);
		detail.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		right.add(detail);
		if (watch != null)
		{
			JLabel open = link("Watch", watch);
			open.setToolTipText("Watch it in your house.");
			right.add(open);
		}
		row.add(right, BorderLayout.EAST);
		// BoxLayout stretches a row to the height it is given; one line is all it needs.
		row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
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
		if (recover)
		{
			actions.add(retry);
			actions.add(discard);
		}
		actions.setVisible(recover);
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
