package org.quark.file.browser;

import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
	private final SettableValue<String> theViewText;
	private final SettableValue<Boolean> isReading;
	private final SettableValue<String> theSearch;
	private final SettableValue<String> isSearching;

	private long theFileModTime;

	private final StringBuilder theTextBuffer;
	private Rectangle2D theCharBounds;
	private ObservableTextArea<String> theTextComponent;
	private final LinkedList<Long> theScrollPositions;
	private boolean isControlledSeek;

	public FileContentViewer(ObservableValue<ObservableFile> file) {
		theFile = file;
		theStart = SettableValue.build(long.class).withValue(0L).build()//
				.filterAccept(start -> start < 0 ? "Negative position not acceptable" : null);
		theEnd = SettableValue.build(long.class).withValue(0L).build();
		theFileSize = SettableValue.build(long.class).withValue(0L).build();
		canScrollBack = SettableValue.build(boolean.class).withValue(false).build();
		canScrollForward = SettableValue.build(boolean.class).withValue(true).build();
		theViewMode = SettableValue.build(String.class).withValue("Binary").build();
		theViewText = SettableValue.build(String.class).withValue("Binary").build();
		isReading = SettableValue.build(boolean.class).withValue(false).build();
		theTextBuffer = new StringBuilder();
		theScrollPositions = new LinkedList<>();
		isSearching = SettableValue.build(String.class).withValue(null).build();
		theSearch = SettableValue.build(String.class).withValue("").build().disableWith(isSearching);

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
							.addComboField(null, theViewMode.disableWith(reading),
									Arrays.asList("Binary", "UTF-8", "UTF-16", "Binary/UTF-8"), null)//
							.addTextField(null, theSearch.disableWith(reading), Format.TEXT, f -> f.modifyEditor(tf -> tf.withColumns(10)))//
							.addLabel(null, isSearching.map(s -> s == null ? "" : s), Format.TEXT, null)//
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
		Observable.or(theStart.noInitChanges(), theViewMode.noInitChanges()).act(evt -> {
			if (changing[0]) {
				return;
			}
			if (!isControlledSeek) {
				theScrollPositions.clear();
			}
			theViewText.set("", evt);
			canScrollBack.set(!theScrollPositions.isEmpty(), evt);
			theEnd.set(theStart.get(), evt);
			isReading.set(true, evt);
			QommonsTimer.getCommonInstance().offload(this::readData);
		});
		QommonsTimer.getCommonInstance().offload(this::readData);
		theSearch.noInitChanges().act(evt -> {
			isSearching.set("Searching...", evt);
			QommonsTimer.getCommonInstance().offload(() -> {
				if (evt.getNewValue().length() > 0) {
					try {
						long[] found = searchFor(evt.getNewValue());
						EventQueue.invokeLater(() -> {
							if (found != null) {
								theStart.set(found[0], null);
								JOptionPane.showMessageDialog(this, "\"" + evt.getNewValue() + "\" found at position " + found[1],
										"Text found", JOptionPane.INFORMATION_MESSAGE);
							} else {
								JOptionPane.showMessageDialog(this, "\"" + evt.getNewValue() + "\" not found", "Text not found",
										JOptionPane.INFORMATION_MESSAGE);
							}
						});
					} catch (IOException e) {
						e.printStackTrace();
						ObservableSwingUtils.onEQ(() -> {
							JOptionPane.showMessageDialog(this, "Could not read file " + theFile.get(), "File Read Failure",
									JOptionPane.ERROR_MESSAGE);
						});
					}
				}
				isSearching.set(null, null);
			});
		});
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
			if (viewMode.startsWith("Binary")) {
				long end = start;
				boolean withText = viewMode.endsWith("UTF-8");
				StringUtils.CharAccumulator chars = new StringUtils.AppendableWriter<>(line);
				int byteCount;
				ByteArrayOutputStream buffer = new ByteArrayOutputStream();
				line.append(start).append(": ");
				for (read = in.read(), byteCount = 0; read >= 0; read = in.read(), byteCount++) {
					if (byteCount > 0 && byteCount % 4 == 0) {
						if (line.length() + 9 > maxLineLength) {
							lineCount++;
							if (lineCount >= maxLines - 1) {
								break;
							}
							lastLineStart = end;
							addLine(line, end, false);
							int spaces = line.indexOf(": ") + 2;
							line.setLength(0);
							if (withText) {
								for (int i = 0; i < spaces; i++) {
									line.append(' ');
								}
								CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(buffer.toByteArray()));
								@SuppressWarnings("resource")
								Reader reader = new InputStreamReader(counting, Charset.forName("UTF-8"));
								int c = 0;
								boolean hadChars = false;
								for (int ch = reader.read(); ch >= 0; ch = reader.read(), c++) {
									if (c > 0 && c % 4 == 0) {
										line.append(' ');
									}
									if (ch == '\b') {
										hadChars = true;
										line.append("\\b");
									} else if (ch == '\r') {
										hadChars = true;
										line.append("\\r");
									} else if (ch == '\n') {
										hadChars = true;
										line.append("\\n");
									} else if (ch == '\t') {
										hadChars = true;
										line.append("\\t");
									} else if (ch == '\f') {
										hadChars = true;
										line.append("\\f");
									} else if (ch == ' ') {
										hadChars = true;
										line.append("sp");
									} else if (ch < ' ') {
										line.append("  ");
									} else {
										hadChars = true;
										line.append((char) ch).append(' ');
									}
								}
								if (hadChars) {
									addLine(line, end, false);
									lineCount++;
								}
								line.setLength(0);
							}
							buffer.reset();
							line.append(start + byteCount).append(": ");
						} else {
							line.append(' ');
						}
					}
					buffer.write(read);
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

	private long[] searchFor(String content) throws IOException {
		ObservableFile file = theFile.get();
		if (file == null) {
			throw new IOException("No file selected");
		}
		long length = file.length();
		BooleanSupplier canceled = () -> false;
		try (InputStream in = file.read(theStart.get(), canceled)) {
			CountingInputStream counting = new CountingInputStream(new BufferedInputStream(in));
			@SuppressWarnings("resource")
			Reader reader = new InputStreamReader(counting, Charset.forName(theViewMode.get().equals("UTF-16") ? "UTF-16" : "UTF-8"));
			int matchLength = 0;
			long lastLine = 0, lastLineBut1 = 0;
			EventQueue.invokeLater(() -> {
				isSearching.set("Searching...0%", null);
			});
			int charCount = 0;
			int startPos = 0;
			for (int c = reader.read(); c >= 0; c = reader.read()) {
				if (charCount == 1_000_000) {
					charCount = 0;
					int newPercent = (int) (counting.getPosition() * 1000.0 / length);
					EventQueue.invokeLater(() -> {
						isSearching.set(new StringBuilder("Searching...").append(newPercent / 10).append('.').append(newPercent % 10)
								.append('%').toString(), null);
					});
				}
				if (c == '\n' || c == 0) {
					lastLineBut1 = lastLine;
					lastLine = counting.getPosition();
					matchLength = 0;
				} else if (c == content.charAt(matchLength)) {
					if (matchLength == 0) {
						startPos=0;
					}
					matchLength++;
					if (matchLength == content.length()) {
						break;
					}
				} else {
					matchLength=0;
				}
				charCount++;
			}
			if (matchLength == content.length()) {
				return new long[] { lastLineBut1, startPos };
			} else {
				return null;
			}
		} catch (IOException e) {
			if (file.isDirectory()) {
				return null;
			}
			throw e;
		}
	}
}
