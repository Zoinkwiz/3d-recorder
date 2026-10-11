package com.extrinsiccognition.embertide.viewer;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JSlider;
import javax.swing.JComboBox;
import javax.swing.JSpinner;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultCaret;
import net.runelite.client.ui.PluginPanel;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WatchPanelTest
{
	private static void update(WatchPanel panel, String message, double elapsed)
	{ panel.update("example.embertide", message, "12 actors · 96 objects", elapsed, 90, true, true, true, true, false); }

	@Test public void playbackRefreshDoesNotReplaceUnchangedDocuments() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			WatchPanel panel = new WatchPanel(new Viewer()); update(panel, "Placed", 0);
			AtomicInteger changes = new AtomicInteger();
			for (JTextArea area : find(panel, JTextArea.class))
			{
				area.getDocument().addDocumentListener(new DocumentListener()
				{
					public void insertUpdate(DocumentEvent e) { changes.incrementAndGet(); }
					public void removeUpdate(DocumentEvent e) { changes.incrementAndGet(); }
					public void changedUpdate(DocumentEvent e) { changes.incrementAndGet(); }
				});
			}
			for (int i = 1; i <= 30; i++) { update(panel, "Placed", i * .2); }
			assertEquals("Playback must not clear and reinsert sidebar text", 0, changes.get());
		});
	}
	@Test public void changingStatusCannotAskItsCaretToScrollTheSidebar() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			WatchPanel panel = new WatchPanel(new Viewer());
			update(panel, "A changing status", 1);
			for (JTextArea area : find(panel, JTextArea.class))
			{
				assertEquals(DefaultCaret.NEVER_UPDATE, ((DefaultCaret) area.getCaret()).getUpdatePolicy());
				assertFalse(area.isFocusable());
			}
		});
	}
	@Test public void refreshKeepsManualScrollAndAnActiveTimelineDrag() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			WatchPanel panel = new WatchPanel(new Viewer());
			// The sidebar scrolls the recorder's panel with this section in it.
			PluginPanel sidebar = new PluginPanel() { }; sidebar.add(panel);
			sidebar.getWrappedPanel().setSize(242, 420); layout(sidebar.getWrappedPanel());
			JScrollPane scroll = find(sidebar.getWrappedPanel(), JScrollPane.class).get(0);
			scroll.getViewport().setViewPosition(new Point(0, 230));
			Point before = scroll.getViewport().getViewPosition();
			JSlider slider = find(panel, JSlider.class).get(0);
			slider.setValueIsAdjusting(true); slider.setValue(2300);
			for (int i = 0; i < 30; i++) { update(panel, "Pending assets: " + i, i); }
			assertEquals(before, scroll.getViewport().getViewPosition());
			assertEquals(2300, slider.getValue());
		});
	}
	@Test public void longExtensionlessFilenameDoesNotBreakTheRefresh() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			WatchPanel panel = new WatchPanel(new Viewer());
			panel.update("a".repeat(100), "Loaded", "", 0, 10, false, true, false, true, false);
			assertTrue(find(panel, JTextArea.class).stream().anyMatch(a -> a.getText().equals("a".repeat(42) + "...")));
		});
	}
	@Test public void lifeSizeIsTheOnlyViewChoiceLeftInTheTab() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			Viewer viewer = mock(Viewer.class); WatchPanel panel = new WatchPanel(viewer);
			assertTrue(find(panel, JSpinner.class).isEmpty());
			@SuppressWarnings("unchecked") JComboBox<String> mode = find(panel, JComboBox.class).get(0);
			mode.setSelectedIndex(1); verify(viewer).miniature(false);
		});
	}
	private static void layout(Container component)
	{ component.doLayout(); for (Component child : component.getComponents()) { if (child instanceof Container) { layout((Container) child); } } }
	public static <T> List<T> find(Container parent, Class<T> type)
	{
		List<T> found = new ArrayList<>();
		for (Component child : parent.getComponents())
		{
			if (type.isInstance(child)) { found.add(type.cast(child)); }
			if (child instanceof Container) { found.addAll(find((Container) child, type)); }
		}
		return found;
	}
}
