package org.quark.searcher;

import java.awt.Desktop;
import java.awt.EventQueue;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.observe.Observable;
import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.assoc.ObservableMap;
import org.observe.collect.ObservableCollection;
import org.observe.collect.ObservableSortedSet;
import org.observe.config.ObservableConfig;
import org.observe.config.SyncValueSet;
import org.observe.util.TypeTokens;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.ObservableCellRenderer;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.PanelPopulation;
import org.qommons.QommonsUtils;
import org.qommons.QommonsUtils.NamedGroupCapture;
import org.qommons.StringUtils;
import org.qommons.TimeUtils.DateElementType;
import org.qommons.TimeUtils.RelativeTimeEvaluation;
import org.qommons.collect.ElementId;
import org.qommons.io.ArchiveEnabledFileSource;
import org.qommons.io.BetterFile;
import org.qommons.io.BetterFile.FileBooleanAttribute;
import org.qommons.io.FileUtils;
import org.qommons.io.Format;
import org.qommons.io.NativeFileSource;
import org.qommons.io.SpinnerFormat;
import org.qommons.threading.QommonsTimer;

public class SearcherUi extends JPanel {
	private final Format<String> PATTERN_FORMAT = new Format<String>() {
		@Override
		public void append(StringBuilder text, String value) {
			if (value != null) {
				text.append(value);
			}
		}

		@Override
		public String parse(CharSequence text) throws ParseException {
			if (text == null || text.length() == 0) {
				return null;
			}
			try {
				Pattern.compile(text.toString());
			} catch (PatternSyntaxException e) {
				throw new ParseException("Bad pattern", 0);
			}
			return text.toString();
		}
	};

	public static interface FileAttributeMapEntry {
		FileBooleanAttribute getAttribute();

		FileAttributeRequirement getValue();

		FileAttributeMapEntry setValue(FileAttributeRequirement value);
	}

	static class SearchResultNode {
		final SearchResultNode parent;
		ElementId parentChildId;
		final BetterFile file;
		Map<String, NamedGroupCapture> matchGroups;
		int fileMatches;
		int contentMatches;
		final ObservableSortedSet<SearchResultNode> children;
		ObservableCollection<TextResult> textResults;

		SearchResultNode(SearchResultNode parent, BetterFile file) {
			this.parent = parent;
			this.file = file;
			children = ObservableSortedSet
					.build(SearchResultNode.class,
							(r1, r2) -> StringUtils.compareNumberTolerant(r1.file.getName(), r2.file.getName(), true, true))
					.safe(false).build();
			textResults = ObservableCollection.build(TextResult.class).safe(false).build();
		}

		SearchResultNode getChild(BetterFile child) {
			for (SearchResultNode ch : children) {
				if (ch.file.equals(child)) {
					return ch;
				}
			}
			SearchResultNode node = new SearchResultNode(this, child);
			node.parentChildId = children.addElement(node, false).getElementId();
			return node;
		}

		void update(int newFileMatches, int newContentMatches) {
			fileMatches += newFileMatches;
			contentMatches += newContentMatches;
			if (parent != null) {
				parent.children.mutableElement(parentChildId).set(this);
				parent.update(newFileMatches, newContentMatches);
			}
		}

		@Override
		public String toString() {
			return file.toString();
		}
	}

	public static class TextResult {
		final SearchResultNode fileResult;
		final long position;
		final long lineNumber;
		final long columnNumber;
		final String value;
		final Map<String, NamedGroupCapture> captures;

		TextResult(SearchResultNode fileResult, long position, long lineNumber, long columnNumber, String value,
				Map<String, NamedGroupCapture> captures) {
			this.fileResult = fileResult;
			this.position = position;
			this.lineNumber = lineNumber;
			this.columnNumber = columnNumber;
			this.value = value;
			this.captures = captures;
		}

		@Override
		public String toString() {
			return value;
		}
	}

	enum SearchStatus {
		Idle, Searching, Canceling
	}

	private final BetterFile.FileFormat theFileFormat;
	private final BetterFile.FileFormat theTestFileFormat;
	private final ArchiveEnabledFileSource theFileSource;
	private final SettableValue<BetterFile> theSearchBase;
	private final SettableValue<Pattern> theFileNamePattern;
	private final SettableValue<String> theFileNamePatternStr;
	private final SettableValue<Boolean> theFileCaseSensitive;
	private final SettableValue<BetterFile> theFileNameTest;
	private final SettableValue<Boolean> isFollowingSymbolicLinks;
	private final SettableValue<Pattern> theFileContentPattern;
	private final SettableValue<String> theFileContentPatternStr;
	private final SettableValue<Boolean> theContentCaseSensitive;
	private final SettableValue<Integer> theMaxFileMatchLength;
	private final SettableValue<String> theContentTest;
	private final SettableValue<Boolean> isSearchingMultipleContentMatches;
	private final SettableValue<Integer> theZipLevel;
	private final ObservableMap<FileBooleanAttribute, FileAttributeRequirement> theBooleanAttributes;
	private final SyncValueSet<PatternConfig> theExclusionPatterns;
	private volatile List<Pattern> theDynamicExclusionPatterns;
	private final SettableValue<Long> theMinSize;
	private final SettableValue<Long> theMaxSize;
	private final SettableValue<Long> theMinTime;
	private final SettableValue<Long> theMaxTime;

	private final SettableValue<SearchResultNode> theResults;
	private final SettableValue<SearchResultNode> theSelectedResult;
	private final SettableValue<TextResult> theSelectedTextResult;
	private final ObservableValue<String> theSelectedRenderedText;
	private volatile boolean isCanceling;
	private final SettableValue<SearchStatus> theStatus;
	private final SettableValue<String> theStatusMessage;

	private final QommonsTimer.TaskHandle theStatusUpdateHandle;

	private volatile BetterFile theCurrentSearch;

	public SearcherUi(ObservableConfig config, String workingDir) {
		theFileSource = new ArchiveEnabledFileSource(new NativeFileSource())//
				.withArchival(new ArchiveEnabledFileSource.ZipCompression());
		BetterFile workingDirFile = BetterFile.at(theFileSource, workingDir);
		theFileFormat = new BetterFile.FileFormat(theFileSource, workingDirFile, false);
		theTestFileFormat = new BetterFile.FileFormat(theFileSource, theFileFormat.getWorkingDir(), true);
		theStatus = SettableValue.build(SearchStatus.class).safe(false).withValue(SearchStatus.Idle).build();
		ObservableValue<String> disable = theStatus.map(st -> st == SearchStatus.Idle ? null : "Cannot be modified during a search");
		theSearchBase = config.asValue(BetterFile.class).at("root").withFormat(theFileFormat, theFileFormat::getWorkingDir)
				.buildValue(null).disableWith(disable);
		SettableValue<PatternConfig> fileNamePattern = config.asValue(PatternConfig.class).at("file-name").buildValue(null);
		theFileNamePatternStr = fileNamePattern
				.asFieldEditor(TypeTokens.get().STRING, PatternConfig::getPattern, PatternConfig::setPattern, null)
				.disableWith(disable);
		theFileNamePattern = fileNamePattern.asFieldEditor(TypeTokens.get().of(Pattern.class), //
				pc -> pc.getPattern() == null ? null
						: Pattern.compile(pc.getPattern(), pc.isCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE), //
				(pc, patt) -> {
					pc.setPattern(patt == null ? null : patt.pattern());
					if (patt != null) {
						pc.setCaseSensitive((patt.flags() & Pattern.CASE_INSENSITIVE) == 0);
					}
				}, null).disableWith(disable);
		theFileCaseSensitive = fileNamePattern.asFieldEditor(TypeTokens.get().BOOLEAN, PatternConfig::isCaseSensitive,
				PatternConfig::setCaseSensitive, null).disableWith(disable);
		theFileNameTest = config.asValue(BetterFile.class).at("file-name/test")
				.withFormat(theTestFileFormat, theTestFileFormat::getWorkingDir).buildValue(null);
		isFollowingSymbolicLinks = config.asValue(boolean.class).at("follow-sym-links").withFormat(Format.BOOLEAN, () -> true)
				.buildValue(null).disableWith(disable);
		SettableValue<PatternConfig> fileContentPattern = config.asValue(PatternConfig.class).at("file-content").buildValue(null);
		theFileContentPatternStr = fileContentPattern.asFieldEditor(TypeTokens.get().STRING, PatternConfig::getPattern,
				PatternConfig::setPattern, null).disableWith(disable);
		int fcFlags = Pattern.MULTILINE;
		theFileContentPattern = fileContentPattern.asFieldEditor(TypeTokens.get().of(Pattern.class), //
				pc -> pc.getPattern() == null ? null
						: Pattern.compile(pc.getPattern(), fcFlags | (pc.isCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE)), //
				(pc, patt) -> {
					pc.setPattern(patt == null ? null : patt.pattern());
					if (patt != null) {
						pc.setCaseSensitive((patt.flags() & Pattern.CASE_INSENSITIVE) == 0);
					}
				}, null).disableWith(disable);
		theContentCaseSensitive = fileContentPattern.asFieldEditor(TypeTokens.get().BOOLEAN, PatternConfig::isCaseSensitive,
				PatternConfig::setCaseSensitive, null).disableWith(disable);
		theMaxFileMatchLength = config.asValue(int.class).at("file-content/max-length").withFormat(Format.INT, () -> 10000).buildValue(null)
				.disableWith(disable);
		theContentTest = config.asValue(String.class).at("file-content/test").withFormat(Format.TEXT, () -> null).buildValue(null);
		isSearchingMultipleContentMatches = config.asValue(boolean.class).at("file-content/multiple")
				.withFormat(Format.BOOLEAN, () -> false).buildValue(null).disableWith(disable);
		theZipLevel = config.asValue(int.class).at("zip-level").withFormat(Format.INT, () -> 10).buildValue(null);
		theZipLevel.changes().act(evt -> theFileSource.setMaxArchiveDepth(evt.getNewValue()));
		SyncValueSet<FileAttributeMapEntry> attrCollection = config.asValue(FileAttributeMapEntry.class).at("file-attributes")
				.asEntity(null).buildEntitySet(null);
		theBooleanAttributes = attrCollection.getValues().flow()
				.groupBy(//
						flow -> flow.map(FileBooleanAttribute.class, FileAttributeMapEntry::getAttribute).distinct(), //
						(att, fame) -> fame)//
				.withValues(values -> values.transform(FileAttributeRequirement.class,
						tx -> tx.map(fame -> fame == null ? null : fame.getValue()).modifySource(//
								(fame2, req) -> fame2.setValue(req))))//
				.gatherActive(null).singleMap(false);
		for (FileBooleanAttribute att : FileBooleanAttribute.values()) {
			FileAttributeRequirement req = theBooleanAttributes.get(att);
			if (req == null) {
				attrCollection.create()//
						.with(FileAttributeMapEntry::getAttribute, att)//
						.with(FileAttributeMapEntry::getValue, FileAttributeRequirement.Maybe)//
						.create();
			}
		}

		theExclusionPatterns = config.asValue(PatternConfig.class).buildEntitySet(null);
		theDynamicExclusionPatterns = evaluatePatterns(theExclusionPatterns.getValues());
		theExclusionPatterns.getValues().simpleChanges().act(__ -> {
			theDynamicExclusionPatterns = evaluatePatterns(theExclusionPatterns.getValues());
		});
		// Doesn't seem like a good idea to remember these values in config.
		// A user just popping up the app and doing a search could inadvertently be excluding files on these criteria
		// just because they forgot to reset them.
		theMinSize = SettableValue.build(long.class).safe(false).withValue(0L).build();
		theMaxSize = SettableValue.build(long.class).safe(false).withValue(1024L * 1024 * 1024 * 1024 * 1024).build(); // 1 Petabyte
		theMaxSize.changes().act(evt -> {
			System.out.println(evt);
		});
		Calendar cal = Calendar.getInstance();
		cal.set(cal.get(Calendar.YEAR) + 100, 1, 1, 0, 0, 0);
		theMaxTime = SettableValue.build(long.class).safe(false).withValue(cal.getTimeInMillis()).build();
		cal.set(1900, 1, 1, 0, 0);
		theMinTime = SettableValue.build(long.class).safe(false).withValue(cal.getTimeInMillis()).build();

		theResults = SettableValue.build(SearchResultNode.class).safe(false).build();
		theSelectedResult = SettableValue.build(SearchResultNode.class).safe(false).build();
		theResults.noInitChanges().act(evt -> theSelectedResult.set(null, evt));
		theSelectedTextResult = SettableValue.build(TextResult.class).safe(false).build();
		theStatusMessage = SettableValue.build(String.class).safe(false).build();
		theStatusMessage.set(getIdleStatus(), null);
		theSelectedRenderedText = theSelectedTextResult.map(tr -> tr == null ? "" : renderTextResult(tr));

		theStatusUpdateHandle = QommonsTimer.getCommonInstance().build(this::updateStatus, Duration.ofMillis(100), false).onEDT();

		initComponents();
	}

	private List<Pattern> evaluatePatterns(ObservableCollection<? extends PatternConfig> patterns) {
		List<Pattern> evald = new ArrayList<>(patterns.size());
		for (PatternConfig pc : patterns) {
			try {
				evald.add(Pattern.compile(pc.getPattern(), pc.isCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE));
			} catch (RuntimeException e) {
			}
		}
		return evald;
	}

	private void updateStatus() {
		String status;
		BetterFile f = theCurrentSearch;
		if (f != null) {
			status = f.getPath();
		} else {
			status = getIdleStatus();
		}
		theStatusMessage.set(status, null);
	}

	private String getDisabledStatus() {
		if (!theSearchBase.get().exists()) {
			return theSearchBase.get() + " does not exist";
		}
		return null;
	}

	private String getIdleStatus() {
		if (theSearchBase.get() == null) {
			return "Choose a folder to search in";
		} else if (theFileNamePattern.get() == null) {
			return "Select a valid file pattern";
		} else {
			return "Ready to search";
		}
	}

	private String testFileName(BetterFile file) {
		return testFileName(theFileNamePattern.get(), file.getPath()) == null ? "File name does not match" : null;
	}

	private Matcher testFileName(Pattern filePattern, CharSequence path) {
		if (filePattern == null) {
			return null;
		}
		Matcher matcher = filePattern.matcher(path);
		// The path must END with the pattern
		boolean found = matcher.find();
		while (found && matcher.end() != path.length()) {
			found = matcher.find(matcher.start() + 1);
		}
		return found ? matcher : null;
		// BetterFile f = file;
		// while (f != null) {
		// seq.advance(f.getName());
		// Matcher matcher = filePattern.matcher(seq);
		// if (matcher.matches()) {
		// return matcher;
		// }
		// f = f.getParent();
		// }
		// return null;
	}

	static class SubSeq implements CharSequence {
		private final CharSequence theMain;
		private final int theStart;
		private final int theEnd;

		SubSeq(CharSequence main, int start, int end) {
			if (start < 0 || end > main.length() || start > end) {
				throw new IndexOutOfBoundsException(start + "..." + end + " of " + main.length());
			}
			theMain = main;
			theStart = start;
			theEnd = end;
		}

		@Override
		public int length() {
			return theEnd - theStart;
		}

		@Override
		public char charAt(int index) {
			if (index < 0) {
				throw new IndexOutOfBoundsException(index + " of " + length());
			}
			int mainIndex = theStart + index;
			if (mainIndex >= theEnd) {
				throw new IndexOutOfBoundsException(index + " of " + length());
			}
			return theMain.charAt(mainIndex);
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			if (start < 0 || end > length() || start > end) {
				throw new IndexOutOfBoundsException(start + "..." + end + " of " + length());
			}
			return new SubSeq(theMain, theStart + start, theStart + end);
		}

		@Override
		public String toString() {
			return new StringBuilder().append(theMain, theStart, theEnd).toString();
		}
	}

	private String testFileContent(String content) {
		if (content == null) {
			return null;
		}
		Pattern p = theFileContentPattern.get();
		if (p == null) {
			return null;
		}
		Matcher m = p.matcher(content);
		if (m.find()) {
			return null;
		}
		return "No " + theFileContentPattern.get().pattern() + " match found";
	}

	private List<TextResult> testFileContent(Supplier<SearchResultNode> fileResult, Pattern contentPattern, BetterFile file,
			boolean searchMulti, FileContentSeq seq) {
		List<TextResult> results = Collections.emptyList();
		try (Reader reader = new BufferedReader(new InputStreamReader(file.read()))) {
			while (seq.advance(reader, -1)) {
				Matcher m = contentPattern.matcher(seq);
				if (m.find()) {
					if (m.start() > 0) {
						seq.advance(reader, m.start());
						m = contentPattern.matcher(seq);
						if (!m.lookingAt()) {
							System.err.println("Lost a match?");
							continue;
						}
					}
					if (results.isEmpty()) {
						results = new ArrayList<>(searchMulti ? 1 : 5);
					}
					results.add(makeTextResult(fileResult.get(), m, seq.getPosition(), seq.getLine(), seq.getColumn()));
					if (!searchMulti) {
						break;
					}
				}
			}
		} catch (IOException e) {
			System.err.println(e);
		}
		return results;
	}

	private TextResult makeTextResult(SearchResultNode fileResult, Matcher matcher, long position, long lineNumber, long columnNumber) {
		return new TextResult(fileResult, position, lineNumber, columnNumber, matcher.group(), QommonsUtils.getCaptureGroups(matcher));
	}

	static class FileContentSeq implements CharSequence {
		private char[] theFirstSequence;
		private char[] theSecondSequence;
		private long thePosition;
		private long theLine;
		private long theColumn;
		private int theFirstLength;
		private int theSecondLength;

		FileContentSeq(int testLength) {
			theFirstSequence = new char[testLength];
			theSecondSequence = new char[testLength];
		}

		FileContentSeq clear() {
			thePosition = theLine = theColumn = theFirstLength = theSecondLength = 0;
			return this;
		}

		int getFirstLength() {
			return theFirstLength;
		}

		long getPosition() {
			return thePosition;
		}

		public long getLine() {
			return theLine;
		}

		public long getColumn() {
			return theColumn;
		}

		boolean advance(Reader reader, int length) throws IOException {
			if (length == 0) {
				return true;
			}
			if (length < 0 || length >= theFirstLength) {
				// Discard first sequence and as much as we need to of the second sequence
				// Put the remainder of the second sequence into the first, then fill up both sequences
				thePosition += theFirstLength;
				int lastLine = 0;
				int colPad = 0;
				for (int i = 0; i < theFirstLength; i++) {
					switch (theFirstSequence[i]) {
					case '\n':
						lastLine = i;
						theLine++;
						theColumn = 0;
						colPad = -1;
						break;
					case '\r':
					case '\b':
					case '\f':
						colPad--;
						break;
					case '\t':
						colPad += 3; // Use 4-character tabs for now
						break;
					}
				}
				theColumn += theFirstLength - lastLine + colPad;

				int read;
				if (theFirstLength == 0 || length > theFirstLength) {
					if (length > 0) {
						lastLine = 0;
						colPad = 0;
						for (int i = 0; i < length - theFirstLength; i++) {
							switch (theSecondSequence[i]) {
							case '\n':
								lastLine = i;
								theLine++;
								theColumn = 0;
								colPad = -1;
								break;
							case '\r':
							case '\b':
							case '\f':
								colPad--;
								break;
							case '\t':
								colPad += 3; // Use 4-character tabs for now
								break;
							}
						}
						theColumn += length - theFirstLength - lastLine + colPad;
					}

					if (length == theFirstLength + theSecondLength) {
						theFirstLength = 0;
					} else if (length > 0) {
						int newLen = theFirstLength + theSecondLength - length;
						System.arraycopy(theSecondSequence, length - theFirstLength, theFirstSequence, 0, newLen);
						theFirstLength = newLen;
					} else {
						char[] temp = theFirstSequence;
						theFirstSequence = theSecondSequence;
						theSecondSequence = temp;
						theFirstLength = theSecondLength;
					}
					theSecondLength = 0;
					read = reader.read(theFirstSequence, theFirstLength, theFirstSequence.length - theFirstLength);
					if (read < 0) {
						return false;
					}
					while (read >= 0 && theFirstLength + read < theFirstSequence.length) {
						theFirstLength += read;
						read = reader.read(theFirstSequence, theFirstLength, theFirstSequence.length - theFirstLength);
					}
					if (read > 0) {
						theFirstLength += read;
						read = reader.read(theSecondSequence, 0, theSecondSequence.length);
						while (read >= 0 && theSecondLength + read < theSecondSequence.length) {
							theSecondLength += read;
							read = reader.read(theSecondSequence, 0, theSecondSequence.length);
						}
						if (read > 0) {
							theSecondLength += read;
						}
					}
				} else {
					char[] temp = theFirstSequence;
					theFirstSequence = theSecondSequence;
					theSecondSequence = temp;
					theFirstLength = theSecondLength;
					theSecondLength = 0;
					read = reader.read(theSecondSequence);
					if (read < 0) {
						return false;
					}
					while (read >= 0 && theSecondLength + read < theSecondSequence.length) {
						theSecondLength += read;
						read = reader.read(theSecondSequence, theSecondLength, theSecondSequence.length - theSecondLength);
					}
					if (read > 0) {
						theSecondLength=read;
					}
				}
				return true;
			} else {
				// Discard the given amount of the first sequence, move the rest to the beginning of the array,
				// append content from the second sequence to it till it's full, read into the second sequence
				thePosition += length;
				int lastLine = 0;
				int colPad = 0;
				for (int i = 0; i < length; i++) {
					switch (theFirstSequence[i]) {
					case '\n':
						lastLine = i;
						theLine++;
						theColumn = 0;
						colPad = -1;
						break;
					case '\r':
					case '\b':
					case '\f':
						colPad--;
						break;
					case '\t':
						colPad += 3; // Use 4-character tabs for now
						break;
					}
				}
				theColumn += length - lastLine + colPad;

				int firstRemain = theFirstLength - length;
				System.arraycopy(theFirstSequence, length, theFirstSequence, 0, firstRemain);
				if (theSecondLength > 0) {
					int secondMoved = Math.min(theSecondLength, length);
					System.arraycopy(theSecondSequence, 0, theFirstSequence, firstRemain, secondMoved);
					theSecondLength -= secondMoved;
					System.arraycopy(theSecondSequence, secondMoved, theSecondSequence, 0, theSecondLength);
				} else {
					theFirstLength = firstRemain;
				}
				int read = reader.read(theSecondSequence, theSecondLength, theSecondSequence.length - theSecondLength);
				if (read < 0) {
					return false;
				}
				while (read >= 0 && theSecondLength + read < theSecondSequence.length) {
					theSecondLength += read;
					read = reader.read(theSecondSequence, theSecondLength, theSecondSequence.length - theSecondLength);
				}
				if (read > 0) {
					theSecondLength += read;
				}
				return true;
			}
		}

		public void goToLine(Reader reader, long lineNumber) throws IOException {
			do {
				long endLine = theLine;
				for (int i = 0; i < theFirstLength; i++) {
					if (theFirstSequence[i] == '\n') {
						endLine++;
						if (endLine == lineNumber) {
							advance(reader, i + 1);
							return;
						}
					}
				}
				advance(reader, theFirstLength == 0 ? -1 : theFirstLength);
			} while (theFirstLength > 0);
		}

		@Override
		public int length() {
			return theFirstLength + theSecondLength;
		}

		@Override
		public char charAt(int index) {
			if (index < 0) {
				throw new IndexOutOfBoundsException(index + " of " + length());
			} else if (index < theFirstLength) {
				return theFirstSequence[index];
			} else if (index < theFirstLength + theSecondLength) {
				return theSecondSequence[index - theFirstLength];
			} else {
				throw new IndexOutOfBoundsException(index + " of " + length());
			}
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return new SubSeq(this, start, end);
		}

		@Override
		public String toString() {
			StringBuilder str = new StringBuilder(theFirstLength + theSecondLength);
			str.append(theFirstSequence, 0, theFirstLength).append(theSecondSequence, 0, theSecondLength);
			return str.toString();
		}
	}

	void doSearch() {
		BetterFile file = theSearchBase.get();
		Pattern filePattern = theFileNamePattern.get();
		Pattern contentPattern = theFileContentPattern.get();
		SearchResultNode rootResult = new SearchResultNode(null, file);
		ObservableSwingUtils.onEQ(() -> {
			theResults.set(rootResult, null);
		});
		long start = System.currentTimeMillis();
		boolean succeeded = false;
		int[] searched = new int[2];
		try {
			doSearch(file, filePattern, contentPattern, isSearchingMultipleContentMatches.get(), //
					new HashMap<>(theBooleanAttributes), () -> rootResult, new StringBuilder(),
					new FileContentSeq(theMaxFileMatchLength.get()), false, searched);
			succeeded = true;
		} finally {
			long end = System.currentTimeMillis();
			theStatusUpdateHandle.setActive(false);
			theCurrentSearch = null;
			boolean succ = succeeded;
			EventQueue.invokeLater(() -> {
				theStatus.set(SearchStatus.Idle, null);
				boolean canceled = isCanceling;
				isCanceling = false;
				if (!succ) {
					theStatusMessage.set("Search failed after " + QommonsUtils.printTimeLength(end - start), null);
				} else if (canceled) {
					theStatusMessage.set("Canceled search after " + QommonsUtils.printTimeLength(end - start), null);
				} else {
					theStatusMessage.set("Completed search of "//
							+ searched[0] + " file" + (searched[0] == 1 ? "" : "s") + " and "//
							+ searched[1] + " director" + (searched[1] == 1 ? "y" : "ies") + " and "//
							+ " in " + QommonsUtils.printTimeLength(end - start), null);
				}
			});
		}
	}

	void doSearch(BetterFile file, Pattern filePattern, Pattern contentPattern, boolean searchMultiContent, //
			Map<FileBooleanAttribute, FileAttributeRequirement> booleanAtts, Supplier<SearchResultNode> nodeGetter,
			StringBuilder pathSeq, FileContentSeq contentSeq, boolean hasMatch, int[] searched) {
		if (isCanceling) {
			return;
		}

		int prePathLen = pathSeq.length();
		if (prePathLen > 0) {
			pathSeq.append('/');
		}
		pathSeq.append(file.getName());
		theCurrentSearch = file;
		for (Pattern exclusion : theDynamicExclusionPatterns) {
			if (testFileName(exclusion, pathSeq) != null) {
				if (isCanceling) {
					return;
				}
				pathSeq.setLength(prePathLen);
				return;
			}
		}
		if (isCanceling) {
			return;
		}
		SearchResultNode[] node = new SearchResultNode[1];
		Matcher fileMatcher = testFileName(filePattern, pathSeq);
		if (isCanceling) {
			return;
		}
		boolean dir = file.isDirectory();
		if (filePattern == null || fileMatcher != null) {
			boolean matches = true;
			for (FileBooleanAttribute attr : FileBooleanAttribute.values()) {
				if (!theBooleanAttributes.get(attr).matches(file.get(attr))) {
					matches = false;
					break;
				}
			}
			if (matches && !file.isDirectory()) {
				long size = file.length();
				matches = size >= theMinSize.get() && size <= theMaxSize.get();
				if (matches) {
					long time = file.getLastModified();
					matches = time >= theMinTime.get() && time <= theMaxTime.get();
				}
			}
			List<TextResult> contentMatches;
			if (!matches) {
				contentMatches = Collections.emptyList();
			} else if (contentPattern != null) {
				contentMatches = testFileContent(nodeGetter, contentPattern, file, searchMultiContent, contentSeq.clear());
				matches = !contentMatches.isEmpty();
			} else {
				contentMatches = Collections.emptyList();
				matches = true;
			}
			if (isCanceling) {
				return;
			}
			if (matches) {
				node[0] = nodeGetter.get();
				node[0].matchGroups = filePattern == null ? Collections.emptyMap() : QommonsUtils.getCaptureGroups(fileMatcher);
				if (!contentMatches.isEmpty()) {
					node[0].textResults.addAll(contentMatches);
				}
				node[0].update(1, contentMatches.size());
				if (!hasMatch) {
					hasMatch = true;
					ObservableSwingUtils.onEQ(() -> {
						theSelectedResult.set(node[0], null);
					});
				}
			}
		}
		if (dir) {
			List<? extends BetterFile> children = file.listFiles();
			searched[1]++;
			for (BetterFile child : children) {
				if (isCanceling) {
					return;
				}
				doSearch(child, filePattern, contentPattern, searchMultiContent, booleanAtts, () -> {
					if (node[0] == null) {
						node[0] = nodeGetter.get();
					}
					return node[0].getChild(child);
				}, pathSeq, contentSeq, hasMatch, searched);
			}
		} else {
			searched[0]++;
		}
		pathSeq.setLength(prePathLen);
	}

	String renderTextResult(TextResult result) {
		try (Reader reader = new BufferedReader(new InputStreamReader(result.fileResult.file.read()))) {
			FileContentSeq seq = new FileContentSeq((int) Math.min(1000, result.columnNumber * 5));
			if (result.lineNumber > 3) {
				seq.goToLine(reader, result.lineNumber - 3);
				if (seq.getLine() < result.lineNumber - 3) {
					return "<html><b><font color=\"red\">**Content changed!!**";
				}
			}
			StringBuilder text = new StringBuilder("<html>");
			if (seq.getPosition() > 0) {
				text.append("...<br>\n");
			}
			boolean appending = true;
			boolean hasMore = false;
			while (appending) {
				long charPos = seq.getPosition();
				boolean started = false, ended = false;
				for (int i = 0; i < seq.length(); i++, charPos++) {
					if (!started) {
						if (charPos == result.position) {
							started = true;
							text.append("<b><font color=\"red\">");
						}
					} else if (!ended) {
						if (charPos == result.position + result.value.length()) {
							ended = true;
							text.append("</font></b>");
						} else if (seq.charAt(i) != result.value.charAt((int) (charPos - result.position))) {
							text.append("**Content changed!!**");
							return text.toString();
						}
					} else if (seq.getLine() > result.lineNumber + 6) {
						appending = false;
						hasMore = i < seq.length() || reader.read() > 0;
						break;
					}
					switch (seq.charAt(i)) {
					case '\n':
						text.append("<br>\n");
						break;
					case '\r':
					case '\b':
					case '\f':
						break;
					case '\t':
						text.append("<&nbsp;&nbsp;&nbsp;&nbsp;");
						break;
					case '<':
						text.append("&lt;");
						break;
					case '>':
						text.append("&gt;");
						break;
					case '&':
						text.append("&");
						break;
					default:
						text.append(seq.charAt(i));
						break;
					}
				}
				if (appending) {
					appending = seq.advance(reader, -1);
				}
			}

			if (hasMore) {
				text.append("...");
			}
			return text.toString();
		} catch (IOException e) {
			return "*Could not re-read* " + result.value;
		}
	}

	private void initComponents() {
		PanelPopulation.populateVPanel(this, null)//
				.addSplit(false, mainSplit -> mainSplit.fill().fillV().withSplitProportion(.4)//
						.firstV(this::populateConfigPanel)//
						.lastV(resultPane -> resultPane.addSplit(true, resultSplit -> resultSplit.fill().fillV().withSplitProportion(.5)//
								.firstV(this::populateResultFiles).lastV(this::populateResultContent))//
				)).addLabel("Status:", theStatusMessage, Format.TEXT, f -> f.withFieldName(theStatus.map(s -> s.name() + ":")));
	}

	private void populateConfigPanel(PanelPopulation.PanelPopulator<?, ?> configPanel) {
		SpinnerFormat<Instant> dateFormat = SpinnerFormat.flexDate(Instant::now, "EEE MMM dd, yyyy",
				teo -> teo.withEvaluationType(RelativeTimeEvaluation.Past).withMaxResolution(DateElementType.Minute));
		Format.SuperDoubleFormatBuilder sizeFormatBuilder = Format.doubleFormat(4).withUnit("b", true);
		double k = 1024;
		sizeFormatBuilder.withPrefix("k", k).withPrefix("M", k * k).withPrefix("G", k * k * k).withPrefix("T", k * k * k * k)//
				.withPrefix("P", k * k * k * k * k).withPrefix("E", k * k * k * k * k * k).withPrefix("Z", k * k * k * k * k * k * k)
				.withPrefix("Y", k * k * k * k * k * k * k * k);
		Format<Double> sizeFormat = sizeFormatBuilder.build();
		SettableValue<Long> minTime = theMinTime.filterAccept(t -> {
			if (t > theMaxTime.get()) {
				theMaxTime.set(t, null);
			}
			return null;
		});
		SettableValue<Long> maxTime = theMaxTime.filterAccept(t -> {
			if (t < theMinTime.get()) {
				theMinTime.set(t, null);
			}
			return null;
		});
		SettableValue<Long> minSize = theMinSize.filterAccept(t -> {
			if (t < 0) {
				return "Size must not be negative";
			}
			if (t > theMaxSize.get()) {
				theMaxSize.set(t, null);
			}
			return null;
		});
		SettableValue<Long> maxSize = theMaxSize.filterAccept(t -> {
			if (t < 0) {
				return "Size must not be negative";
			}
			if (t < theMinSize.get()) {
				theMinSize.set(t, null);
			}
			return null;
		});

		configPanel
				.addHPanel("Search In:", "box",
						rootPanel -> rootPanel.fill()//
								.addTextField(null, theSearchBase, theFileFormat, tf -> tf.fill())//
								.addFileField(null, theSearchBase.map(FileUtils::asFile, f -> BetterFile.at(theFileSource, f.getPath())),
										true, null))//
				.spacer(3)
				.addHPanel(null, new JustifiedBoxLayout(false).mainCenter(),
						p -> p.addLabel(null, ObservableValue.of("----File Name----"), Format.TEXT, x -> x.fill()))//
				.addHPanel("File Pattern:", "box",
						fpPanel -> fpPanel.fill()//
								.addTextField(null, theFileNamePatternStr, PATTERN_FORMAT,
										tf -> tf.fill().withTooltip("The regular expression to match file names (and paths) with"))//
								.addCheckField("Case:",
										theFileCaseSensitive
												.disableWith(theFileNamePattern.map(p -> p == null ? "No file pattern set" : null)),
										ck -> ck.withTooltip("Whether the file pattern should be evaluated case-sensitively")))//
				.addHPanel("Test File:", "box",
						rootPanel -> rootPanel.fill()//
								.addTextField(null,
										theFileNameTest
												.refresh(//
														Observable.or(theSearchBase.noInitChanges(), theFileNamePattern.noInitChanges()))//
												.disableWith(theFileNamePattern.map(p -> p == null ? "No file pattern set" : null)), //
										theTestFileFormat,
										tf -> tf.fill().modifyEditor(tf2 -> tf2.withWarning(this::testFileName)))//
								.addFileField(null, theFileNameTest.map(FileUtils::asFile, f -> BetterFile.at(theFileSource, f.getPath())),
										true, null))//
				.spacer(3)
				.addHPanel(null, new JustifiedBoxLayout(false).mainCenter(),
						p -> p.addLabel(null, ObservableValue.of("----File Content----"), Format.TEXT, x -> x.fill()))//
				.addHPanel("Text Pattern:", "box",
						cpPanel -> cpPanel.fill()//
								.addTextField(null, theFileContentPatternStr, PATTERN_FORMAT,
										tf -> tf.fill().withTooltip("The regular expression to match file content with"))//
								.addCheckField("Case:",
										theContentCaseSensitive
												.disableWith(theFileContentPattern.map(p -> p == null ? "No content pattern set" : null)),
										ck -> ck.withTooltip("Whether the content pattern should be evaluated case-sensitively")))//
				.addTextField("Test Content:",
						theContentTest
								.refresh(//
										theFileContentPattern.noInitChanges())//
								.disableWith(theFileContentPattern.map(p -> p == null ? "No content pattern set" : null))//
								.map(v -> v == null ? "" : v, v -> v),
						Format.TEXT, tf -> tf.fill().modifyEditor(tf2 -> tf2.withWarning(this::testFileContent)))//
				.addCheckField("Multiple Text Matches:",
						isSearchingMultipleContentMatches
								.disableWith(theFileContentPattern.map(p -> p == null ? "No content pattern set" : null)),
						null)//
				.spacer(3)
				.addHPanel(null, new JustifiedBoxLayout(false).mainCenter(),
						p -> p.addLabel(null, ObservableValue.of("----Excluded File Names---"), Format.TEXT, x -> x.fill()))//
				.addTable(theExclusionPatterns.getValues(), exclTable -> {
					exclTable.withColumn("Pattern", String.class, PatternConfig::getPattern,
							c -> c.withWidths(100, 150, 500).withMutation(m -> m.asText(PATTERN_FORMAT)));
					exclTable.withColumn("Case", boolean.class, PatternConfig::isCaseSensitive,
							c -> c.withHeaderTooltip("Case-sensitive")
									.withRenderer(ObservableCellRenderer.checkRenderer(cell -> cell.getCellValue()))
									.withMutation(m -> m.asCheck()));
					exclTable.withAdd(() -> theExclusionPatterns.create().create().get(), null);
					exclTable.withRemove(null, null);
				})//
				.spacer(3)
				.addHPanel(null, new JustifiedBoxLayout(false).mainCenter(),
						p -> p.addLabel(null, ObservableValue.of("----File Metadata----"), Format.TEXT, x -> x.fill()))//
				.addTextField("Max Zip Depth:", theZipLevel, SpinnerFormat.INT, tf -> tf.fill())//
				.addRadioField("Directory:", theBooleanAttributes.observe(FileBooleanAttribute.Directory),
						FileAttributeRequirement.values(), null)//
				.addRadioField("Readable:", theBooleanAttributes.observe(FileBooleanAttribute.Readable),
						FileAttributeRequirement.values(), null)//
				.addRadioField("Writable:", theBooleanAttributes.observe(FileBooleanAttribute.Writable),
						FileAttributeRequirement.values(), null)//
				.addRadioField("Hidden:", theBooleanAttributes.observe(FileBooleanAttribute.Hidden),
						FileAttributeRequirement.values(), null)//
				.addHPanel("Size:", new JustifiedBoxLayout(false).mainJustified(), p -> p.fill()//
						.addTextField(null, minSize.map(s -> s * 1.0, s -> s.longValue()), sizeFormat, f -> f.fill())//
						.addLabel(null, "...", null)//
						.addTextField(null, maxSize.map(s -> s * 1.0, s -> s.longValue()), sizeFormat, f -> f.fill())//
				)//
				.addHPanel("Last Modified:", new JustifiedBoxLayout(false).mainJustified(), p -> p.fill()//
						.addTextField(null, minTime.map(Instant::ofEpochMilli, Instant::toEpochMilli), dateFormat, f -> f.fill())//
						.addLabel(null, "...", null)//
						.addTextField(null, maxTime.map(Instant::ofEpochMilli, Instant::toEpochMilli), dateFormat, f -> f.fill())//
				)//
				.addButton("Search", __ -> {
					SearchStatus status = theStatus.get();
					switch (status) {
					case Idle:
						theStatus.set(SearchStatus.Searching, null);
						theStatusMessage.set("Beginning search...", null);
						theStatusUpdateHandle.setActive(true);
						QommonsTimer.getCommonInstance().offload(this::doSearch);
						break;
					case Searching:
						isCanceling = true;
						theStatus.set(SearchStatus.Canceling, null);
						break;
					case Canceling:
						break;
					}
				}, b -> b.disableWith(theStatus.map(s -> s == SearchStatus.Canceling ? "Canceling Search" : null))//
						.withText(theStatus.map(st -> {
							switch (st) {
							case Searching:
								return "Cancel";
							case Canceling:
								return "Canceling";
							default:
								return "Search";
							}
						})))
		// TODO Size, last modified
		;
	}

	private void populateResultFiles(PanelPopulation.PanelPopulator<?, ?> configPanel) {
		configPanel.addTree(theResults, node -> node.children,
				tree -> tree.fill().fillV().withSelection(theSelectedResult, r -> r.parent, true)//
						.renderWith(node -> node.file.getName())//
						.withLeafTest(node -> !node.file.isDirectory())//
						.onClick(evt -> {
							if (evt.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(evt) && Desktop.isDesktopSupported()) {
								try {
									Desktop.getDesktop().open(new File(theSelectedResult.get().file.getPath()));
								} catch (IOException e) {
									e.printStackTrace();
									configPanel.alert("Could Not Open File",
											"Error occurred opening " + theSelectedResult.get().file.getPath());
								}
							}
						}));
	}

	private void populateResultContent(PanelPopulation.PanelPopulator<?, ?> configPanel) {
		configPanel.addSplit(true, split -> split.visibleWhen(theFileContentPattern.map(v -> v != null)).fill().withSplitProportion(.5)
				.firstV(split1 -> split1.addTable(
						ObservableCollection.flattenValue(theSelectedResult.map(result -> result == null ? null : result.textResults)),
						list -> list.fill().visibleWhen(theFileContentPattern.map(p -> p != null))
								.withSelection(theSelectedTextResult, true)//
								.withColumn("Value", String.class, tr -> tr.value, c -> c.withWidths(100, 250, 1000))//
								.withColumn("Pos", long.class, tr -> tr.position, c -> c.withWidths(30, 50, 100))//
								.withColumn("Line", long.class, tr -> tr.lineNumber + 1, c -> c.withWidths(30, 50, 100))//
								.withColumn("Col", long.class, tr -> tr.columnNumber + 1, c -> c.withWidths(30, 50, 100))//
				)//
				)//
				.lastV(split2 -> split2.addTextArea(null, SettableValue.asSettable(theSelectedRenderedText, __ -> null), Format.TEXT,
						tf -> tf.fill().fillV().modifyEditor(tf2 -> tf2.asHtml(true).setEditable(false)))))//
		;
	}

	public static void main(String[] args) {
		String workingDir = System.getProperty("user.dir");
		ObservableSwingUtils.buildUI()//
				.withConfig("qommons-search")//
				.withTitle("Qommons Search")//
				.systemLandF()//
				.build(config -> new SearcherUi(config, workingDir));
	}
}
