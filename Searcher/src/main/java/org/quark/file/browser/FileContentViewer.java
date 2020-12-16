package org.quark.file.browser;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.observe.Observable;
import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.file.ObservableFile;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.ObservableTextArea;
import org.observe.util.swing.PanelPopulation;
import org.qommons.StringUtils;
import org.qommons.io.BetterFile;
import org.qommons.io.CountingInputStream;
import org.qommons.io.Format;
import org.qommons.threading.QommonsTimer;

public class FileContentViewer extends JPanel {
	private final ObservableValue<ObservableFile> theFile;
	private final SettableValue<Long> theStart;
	private final SettableValue<Long> theEnd;
	private final SettableValue<Long> theFileSize;
	private final SettableValue<Boolean> canScrollBack;
	private final SettableValue<Boolean> canScrollForward;
	private final SettableValue<String> theViewMode;
	private final SettableValue<Boolean> theTextWrap;
	private final SettableValue<String> theViewText;
	private final SettableValue<Boolean> isReading;

	private long theFileModTime;

	private final StringBuilder theTextBuffer;
	private Rectangle2D theCharBounds;
	private ObservableTextArea<String> theTextComponent;
	private final LinkedList<Long> theScrollPositions;
	private boolean isControlledSeek;

	public FileContentViewer(ObservableValue<ObservableFile> file) {
		theFile = file;
		theStart = SettableValue.build(long.class).safe(false).withValue(0L).build()//
				.filterAccept(start -> start < 0 ? "Negative position not acceptable" : null);
		theEnd = SettableValue.build(long.class).safe(false).withValue(0L).build();
		theFileSize = SettableValue.build(long.class).safe(false).withValue(0L).build();
		canScrollBack = SettableValue.build(boolean.class).safe(false).withValue(false).build();
		canScrollForward = SettableValue.build(boolean.class).safe(false).withValue(true).build();
		theViewMode = SettableValue.build(String.class).safe(false).withValue("Binary").build();
		theTextWrap = SettableValue.build(boolean.class).safe(false).withValue(false).build();
		theViewText = SettableValue.build(String.class).safe(false).withValue("Binary").build();
		isReading = SettableValue.build(boolean.class).safe(false).withValue(false).build();
		theTextBuffer = new StringBuilder();
		theScrollPositions = new LinkedList<>();

		ObservableValue<String> reading = isReading.map(r -> r ? "Reading Data" : null);
		PanelPopulation.populateVPanel(this, null)//
				.addHPanel(null, new JustifiedBoxLayout(false).mainJustified(), topPanel -> {
					topPanel.addButton("<<", this::scrollLeft, btn -> btn.disableWith(canScrollBack.map(s -> s ? null : "At beginning")))//
							.addTextField(null, theStart.disableWith(reading), Format.LONG, f -> f.modifyEditor(tf -> tf.withColumns(8)))//
							.addLabel(null, " to ", null)//
							.addLabel(null, theEnd, Format.LONG, null)//
							.addLabel(null, " of ", null)//
							.addLabel(null, theFileSize.map(sz -> sz < 0 ? Double.NaN : sz * 1.0), BetterFileBrowser.SIZE_FORMAT, null)//
							.addButton(">>", this::scrollRight,
									btn -> btn.disableWith(canScrollForward.map(sf -> sf ? null : "At end")).disableWith(reading))//
							.addComboField(null, theViewMode.disableWith(reading), Arrays.asList("Binary", "UTF-8", "UTF-16"), null)//
							.addCheckField("Wrap:",
									theTextWrap.disableWith(theViewMode.map(vm -> vm.equals("Binary") ? vm : null)).disableWith(reading),
									null)//
					;
				})//
				.addTextArea(null, theViewText, Format.TEXT, ta -> ta.fill().fillV().modifyEditor(c -> {
					c.setEditable(false);
					theTextComponent = c;
					Font font = new Font("Courier New", c.getFont().getStyle(), c.getFont().getSize());
					c.setFont(font);
				}))//
		;

		boolean[] changing = new boolean[1];
		theFile.changes().act(evt -> {
			changing[0] = true;
			try {
				if (evt.getNewValue() == null || evt.getNewValue().length() == 0) {
					canScrollBack.set(false, evt);
					theScrollPositions.clear();
					theTextBuffer.setLength(0);
					theStart.set(0L, evt);
					theEnd.set(0L, evt);
					canScrollForward.set(true, evt);
					theViewText.set("", evt);
					return;
				}
				boolean refresh = false;
				long fileSize = evt.getNewValue().length();
				long modTime = evt.getNewValue().getLastModified();
				if (!Objects.equals(evt.getOldValue(), evt.getNewValue())) {
					refresh = true;
				} else if (fileSize != theFileSize.get() || modTime != theFileModTime) {
					refresh = true;
				}
				if (refresh) { // Either a new file selected, or the selected file has changed
					canScrollBack.set(false, evt);
					theScrollPositions.clear();
					theFileModTime = modTime;
					theFileSize.set(fileSize, evt);
					theStart.set(0L, evt);
					theEnd.set(0L, evt);
					theViewText.set("", evt);
					isReading.set(true, evt);
					QommonsTimer.getCommonInstance().offload(this::readData);
				}
			} finally {
				changing[0] = false;
			}
		});
		Observable.or(theStart.noInitChanges(), theViewMode.noInitChanges(), theTextWrap.noInitChanges()).act(evt -> {
			if (changing[0]) {
				return;
			}
			if (!isControlledSeek) {
				theScrollPositions.clear();
			}
			canScrollBack.set(!theScrollPositions.isEmpty(), evt);
			theEnd.set(theStart.get(), evt);
			isReading.set(true, evt);
			QommonsTimer.getCommonInstance().offload(this::readData);
		});
		QommonsTimer.getCommonInstance().offload(this::readData);
	}

	void scrollLeft(Object cause) {
		theScrollPositions.pollLast();
		theScrollPositions.pollLast();
		Long last = theScrollPositions.peekLast();
		isControlledSeek = true;
		try {
			theStart.set(last == null ? 0L : last.longValue(), null);
		} finally {
			isControlledSeek = false;
		}
	}

	void scrollRight(Object cause) {
		isControlledSeek = true;
		try {
			theStart.set(theScrollPositions.getLast(), null);
		} finally {
			isControlledSeek = false;
		}
	}

	void readData() {
		if (theCharBounds == null) {
			Graphics2D g = (Graphics2D) theTextComponent.getGraphics();
			if (g != null && g.getFontRenderContext() != null) {
				theCharBounds = theTextComponent.getFont().getMaxCharBounds(g.getFontRenderContext());
			} else {
				return;
			}
		}
		BetterFile file = theFile.get();
		if (file == null || file.length() == 0) {
			return;
		}
		long start = theStart.get();
		String viewMode = theViewMode.get();
		boolean wrap = theTextWrap.get();
		theTextBuffer.setLength(0);
		int width = theTextComponent.getWidth();
		int height = theTextComponent.getHeight();
		int marginH = theTextComponent.getMargin().left + theTextComponent.getMargin().right;
		int marginV = theTextComponent.getMargin().top + theTextComponent.getMargin().bottom;
		// if (theTextComponent.getParent() instanceof JViewport) {
		// width -= ((JScrollPane) theTextComponent.getParent().getParent()).getVerticalScrollBar().getPreferredSize().width - 5;
		// }
		int maxLines = Math.max(1, (int) ((height - marginV) / Math.ceil(theCharBounds.getHeight()) * 0.9));
		int maxLineLength = Math.max(8, (int) ((width - marginH) / Math.ceil(theCharBounds.getWidth()) * 0.99));
		// System.out.println("Max lines=" + maxLines + ", max length=" + maxLineLength);

		long lastLineStart = start;
		StringBuilder line = new StringBuilder();
		int lineCount = 0;
		BooleanSupplier canceled = () -> false;
		try (InputStream in = file.read(start, canceled)) {
			int read;
			if (viewMode.equals("Binary")) {
				long end = start;
				StringUtils.CharAccumulator chars = new StringUtils.AppendableWriter<>(line);
				int byteCount;
				for (read = in.read(), byteCount = 0; read >= 0; read = in.read(), byteCount++) {
					if (byteCount > 0 && byteCount % 4 == 0) {
						if (line.length() + 9 > maxLineLength) {
							lineCount++;
							if (lineCount == maxLines) {
								break;
							}
							lastLineStart = end;
							addLine(line, end, false);
							line.setLength(0);
						} else {
							line.append(' ');
						}
					}
					StringUtils.printHexByte(chars, read);
					end++;
				}
				if (line.length() > 0) {
					addLine(line, end, read < 0);
				}
			} else {
				CountingInputStream counting = new CountingInputStream(in);
				@SuppressWarnings("resource")
				Reader reader = new InputStreamReader(counting, Charset.forName(viewMode));
				for (read = reader.read(); read >= 0; read = reader.read()) {
					if (read == '\n' || line.length() == maxLineLength) {
						lineCount++;
						if (lineCount == maxLines) {
							break;
						}
						lastLineStart = start + counting.getPosition();
						addLine(line, lastLineStart, false);
						line.setLength(0);
					}
					if (read != '\n' && read != '\r') {
						line.append((char) read);
					}
				}
				if (line.length() > 0) {
					addLine(line, start + counting.getPosition(), read < 0);
				}
			}
		} catch (IOException e) {
			// In the case of compressed files, a BetterFile may be both a directory and a readable file.
			// There's currently no way to tell between an archive file and a pure directory,
			// so we just have to accept the possibility of failure and swallow the error or a directory
			if (file.isDirectory()) {
				return;
			}
			e.printStackTrace();
			ObservableSwingUtils.onEQ(() -> {
				JOptionPane.showMessageDialog(this, "Could not read file " + file.getPath(), "File Read Failure",
						JOptionPane.ERROR_MESSAGE);
			});
		}
		theScrollPositions.add(lastLineStart);
		ObservableSwingUtils.onEQ(() -> {
			isReading.set(false, null);
		});
	}

	private void addLine(CharSequence line, long end, boolean atEnd) {
		if (theTextBuffer.length() > 0) {
			theTextBuffer.append('\n');
		}
		theTextBuffer.append(line);
		String newText = theTextBuffer.toString();
		ObservableSwingUtils.onEQ(() -> {
			theViewText.set(newText, null);
			theEnd.set(end, null);
			canScrollForward.set(!atEnd, null);
		});
	}
}
