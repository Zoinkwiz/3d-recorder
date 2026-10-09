package com.extrinsiccognition.embertide;

import com.extrinsiccognition.embertide.viewer.Viewer;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultCaret;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import org.junit.Test;
import static com.extrinsiccognition.embertide.viewer.WatchPanelTest.find;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** The sidebar scrolls the recorder's panel; its refresh must not move that scroll. */
public class EmbertidePanelTest
{
	private static EmbertidePlugin plugin(Viewer viewer) throws Exception
	{
		EmbertidePlugin plugin = new EmbertidePlugin();
		Field field = EmbertidePlugin.class.getDeclaredField("viewer"); field.setAccessible(true); field.set(plugin, viewer);
		return plugin;
	}
	@Test public void statusRefreshesNeitherRewriteUnchangedTextNorScrollTheSidebar() throws Exception
	{
		EmbertidePlugin plugin = plugin(new Viewer());
		Method render = EmbertidePanel.class.getDeclaredMethod("render", EmbertidePlugin.class); render.setAccessible(true);
		AtomicInteger changes = new AtomicInteger();
		SwingUtilities.invokeAndWait(() ->
		{
			EmbertidePanel panel = new EmbertidePanel(plugin);
			for (JTextArea area : find(panel, JTextArea.class))
			{
				assertEquals(DefaultCaret.NEVER_UPDATE, ((DefaultCaret) area.getCaret()).getUpdatePolicy());
				assertFalse(area.isFocusable());
				area.getDocument().addDocumentListener(new DocumentListener()
				{
					public void insertUpdate(DocumentEvent e) { changes.incrementAndGet(); }
					public void removeUpdate(DocumentEvent e) { changes.incrementAndGet(); }
					public void changedUpdate(DocumentEvent e) { changes.incrementAndGet(); }
				});
			}
			try { for (int i = 0; i < 30; i++) { render.invoke(panel, plugin); } }
			catch (ReflectiveOperationException e) { throw new AssertionError(e); }
		});
		assertEquals("Refreshing the same status must not clear and reinsert it", 0, changes.get());
	}
	@SuppressWarnings("unchecked") @Test public void aPartsWatchLinkOpensThatRecordingInTheWatchTab() throws Exception
	{
		Viewer viewer = mock(Viewer.class); when(viewer.panel()).thenReturn(new JPanel()); EmbertidePlugin plugin = plugin(viewer);
		Field saved = EmbertidePlugin.class.getDeclaredField("saved"); saved.setAccessible(true);
		((List<EmbertidePlugin.SavedFile>) saved.get(plugin)).add(new EmbertidePlugin.SavedFile("part-1.embertide", 1, 65, ""));
		SwingUtilities.invokeAndWait(() ->
		{
			EmbertidePanel panel = new EmbertidePanel(plugin);
			MaterialTab watch = find(panel, MaterialTab.class).stream().filter(t -> t.getText().equals("Watch")).findFirst().orElseThrow();
			JLabel link = find(panel, JLabel.class).stream().filter(l -> l.getText().contains("Watch in your house")).findFirst().orElseThrow();
			assertFalse(watch.isSelected());
			for (MouseListener l : link.getMouseListeners()) { l.mouseClicked(new MouseEvent(link, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false)); }
			assertTrue(watch.isSelected());
		});
		verify(viewer).load("part-1.embertide");
	}
}
