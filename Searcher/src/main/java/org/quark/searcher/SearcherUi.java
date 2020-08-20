package org.quark.searcher;

import java.awt.EventQueue;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.JPanel;

import org.observe.Observable;
import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.assoc.ObservableMap;
import org.observe.collect.ObservableCollection;
import org.observe.collect.ObservableSortedSet;
import org.observe.config.ObservableConfig;
import org.observe.config.SyncValueSet;
import org.observe.util.TypeTokens;
import org.observe.util.swing.ObservableCellRenderer;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.PanelPopulation;
import org.qommons.QommonsUtils;
import org.qommons.QommonsUtils.NamedGroupCapture;
import org.qommons.StringUtils;
import org.qommons.collect.ElementId;
import org.qommons.io.BetterFile;
import org.qommons.io.BetterFile.FileBooleanAttribute;
import org.qommons.io.CompressionEnabledFileSource;
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

	public static interface PatternConfig {
		String getPattern();

		PatternConfig setPattern(String pattern);

		boolean isCaseSensitive();

		PatternConfig setCaseSensitive(boolean caseSensitive);
	}

	public static enum FileAttributeRequirement {
		Mabye, Yes, No;
	}

	public static interface FileAttributeMapEntry {
		FileBooleanAttribute getAttribute();

		FileAttributeRequirement getValue();
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

	static class TextResult {
		final SearchResultNode fileResult;
		final int start;
		final String value;
		final Map<String, NamedGroupCapture> captures;

		TextResult(SearchResultNode fileResult, int start, String value, Map<String, NamedGroupCapture> captures) {
			this.fileResult = fileResult;
			this.start = start;
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
	private final CompressionEnabledFileSource theFileSource;
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
		theFileSource = new CompressionEnabledFileSource(new NativeFileSource())//
				.withCompression(new CompressionEnabledFileSource.ZipCompression());
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
		theMaxFileMatchLength = config.asValue(int.class).at("file-content/max-length").withFormat(Format.INT, () -> 1000).buildValue(null)
				.disableWith(disable);
		theContentTest = config.asValue(String.class).at("file-content/test").withFormat(Format.TEXT, () -> null).buildValue(null);
		isSearchingMultipleContentMatches = config.asValue(boolean.class).at("file-content/multiple")
				.withFormat(Format.BOOLEAN, () -> false).buildValue(null).disableWith(disable);
		theZipLevel = config.asValue(int.class).at("zip-level").withFormat(Format.INT, () -> 10).buildValue(null);
		theZipLevel.changes().act(evt -> theFileSource.setMaxZipDepth(evt.getNewValue()));
		SyncValueSet<FileAttributeMapEntry> attrCollection = config.asValue(FileAttributeMapEntry.class).at("file-attributes")
				.asEntity(null).buildEntitySet(null);
		theBooleanAttributes = ((ObservableCollection<FileAttributeMapEntry>) attrCollection.getValues()).flow()
				.groupBy(//
						flow -> flow.map(TypeTokens.get().of(FileBooleanAttribute.class), FileAttributeMapEntry::getAttribute).distinct(),
						(att, old) -> attrCollection.create().with(FileAttributeMapEntry::getAttribute, att).create().get())//
				.withValues(flow -> flow.map(TypeTokens.get().of(FileAttributeRequirement.class), FileAttributeMapEntry::getValue))//
				.gatherActive(null).singleMap(false);
		theExclusionPatterns = config.asValue(PatternConfig.class).buildEntitySet(null);
		theDynamicExclusionPatterns = evaluatePatterns(theExclusionPatterns.getValues());
		theExclusionPatterns.getValues().simpleChanges().act(__ -> {
			theDynamicExclusionPatterns = evaluatePatterns(theExclusionPatterns.getValues());
		});

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
		return testFileName(theFileNamePattern.get(), file, new PathCharSeq()) == null ? "File name does not match" : null;
	}

	private Matcher testFileName(Pattern filePattern, BetterFile file, PathCharSeq seq) {
		if (filePattern == null || file == null) {
			return null;
		}
		Matcher matcher = filePattern.matcher(file.getName());
		return matcher.matches() ? matcher : null;
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

	static class PathCharSeq implements CharSequence {
		private char[] theSequence;
		private int theLength;

		PathCharSeq() {
			theSequence = new char[4096];
		}

		PathCharSeq reset() {
			return this;
		}

		void advance(String pathName) {
			int newLen = theLength + pathName.length();
			if (newLen > theSequence.length) {
				char[] sequence = new char[theSequence.length * 2];
				System.arraycopy(theSequence, 0, sequence, 0, theSequence.length);
				theSequence = sequence;
			}
			for (int i = 0; i < pathName.length(); i++) {
				theSequence[theLength + i] = pathName.charAt(pathName.length() - i - 1);
			}
			theLength = newLen;
		}

		@Override
		public int length() {
			return theLength;
		}

		@Override
		public char charAt(int index) {
			if (index < 0 || index >= theLength) {
				throw new IndexOutOfBoundsException(index + " of " + theLength);
			}
			return theSequence[theLength - index - 1];
		}
		
		@Override
		public CharSequence subSequence(int start, int end) {
			return new SubSeq(this, start, end);
		}

		@Override
		public String toString() {
			return new String(theSequence, 0, theLength);
		}
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
					if (m.start() > 0 && m.end() == seq.length() && seq.advance(reader, m.start())) {
						m = contentPattern.matcher(seq);
						if (!m.matches()) {
							System.err.println("Lost a match?");
							continue;
						}
					}
					if (results.isEmpty()) {
						results = new ArrayList<>(searchMulti ? 1 : 5);
					}
					results.add(makeTextResult(fileResult.get(), m, seq.getPosition()));
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

	private TextResult makeTextResult(SearchResultNode fileResult, Matcher matcher, int position) {
		return new TextResult(fileResult, position + matcher.start(), matcher.group(), QommonsUtils.getCaptureGroups(matcher));
	}

	static class FileContentSeq implements CharSequence {
		private char[] theFirstSequence;
		private char[] theSecondSequence;
		private int thePosition;
		private int theFirstLength;
		private int theSecondLength;

		FileContentSeq(int testLength) {
			theFirstSequence = new char[testLength];
			theSecondSequence = new char[testLength];
		}

		FileContentSeq clear() {
			thePosition = theFirstLength = theSecondLength = 0;
			return this;
		}

		int getFirstLength() {
			return theFirstLength;
		}

		int getPosition() {
			return thePosition;
		}

		boolean advance(Reader reader, int length) throws IOException {
			if (length == 0) {
				return true;
			}
			if (length < 0 || length == theFirstLength) {
				char[] temp = theFirstSequence;
				theFirstSequence = theSecondSequence;
				theSecondSequence = temp;
				thePosition += theFirstLength;
				theFirstLength = theSecondLength;
				theSecondLength = reader.read(theSecondSequence);
				if (theSecondLength < 0) {
					return false;
				} else if (theFirstLength == 0) {
					advance(reader, -1);
				}
				return true;
			} else if (length > 0 && length < theFirstLength) {
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
				theSecondLength += read;
				return true;
			} else {
				int newLen = theSecondLength - length;
				System.arraycopy(theSecondSequence, length - theFirstLength, theFirstSequence, 0, newLen);
				theFirstLength = newLen;
				int read = reader.read(theFirstSequence, theFirstLength, theFirstSequence.length - theFirstLength);
				if (read < 0) {
					return false;
				}
				theFirstLength += read;
				read = reader.read(theSecondSequence);
				if (read < 0) {
					theSecondLength = 0;
				}
				theSecondLength = read;
				return true;
			}
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
		try {
			doSearch(file, filePattern, contentPattern, isSearchingMultipleContentMatches.get(), //
					new HashMap<>(theBooleanAttributes), () -> rootResult, new PathCharSeq(),
					new FileContentSeq(theMaxFileMatchLength.get()), false);
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
					theStatusMessage.set("Completed search in " + QommonsUtils.printTimeLength(end - start), null);
				}
			});
		}
	}

	void doSearch(BetterFile file, Pattern filePattern, Pattern contentPattern, boolean searchMultiContent, //
			Map<FileBooleanAttribute, FileAttributeRequirement> booleanAtts, Supplier<SearchResultNode> nodeGetter,
			PathCharSeq pathSeq, FileContentSeq contentSeq, boolean hasMatch) {
		if (isCanceling) {
			return;
		}

		theCurrentSearch = file;
		for (Pattern exclusion : theDynamicExclusionPatterns) {
			if (testFileName(exclusion, file, pathSeq.reset()) != null) {
				if (isCanceling) {
					return;
				}
				return;
			}
		}
		if (isCanceling) {
			return;
		}
		SearchResultNode[] node = new SearchResultNode[1];
		Matcher fileMatcher = testFileName(filePattern, file, pathSeq);
		if (isCanceling) {
			return;
		}
		if (fileMatcher!=null) {
			// TODO Attributes
			Map<String, NamedGroupCapture> fileCaptures = QommonsUtils.getCaptureGroups(fileMatcher);
			boolean matches;
			List<TextResult> contentMatches;
			if (contentPattern != null) {
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
				node[0].matchGroups = fileCaptures;
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
		List<? extends BetterFile> children = file.listFiles();
		if (children != null) {
			for (BetterFile child : children) {
				if (isCanceling) {
					return;
				}
				doSearch(child, filePattern, contentPattern, searchMultiContent, booleanAtts, () -> {
					if (node[0] == null) {
						node[0] = nodeGetter.get();
					}
					return node[0].getChild(child);
				}, pathSeq, contentSeq, hasMatch);
			}
		}
	}

	String renderTextResult(TextResult result) {
		try (Reader reader = new BufferedReader(new InputStreamReader(result.fileResult.file.read()))) {
			int position = 0;
			while (position < result.start - 100) {
				int skipped = (int) reader.skip(result.start - 100 - position);
				if (skipped == 0) {
					if (reader.read() < 0) {
						return "*Content changed* " + result.value;
					}
					position++;
				}
				position += skipped;
			}
			char[] buffer = new char[100];
			StringBuilder text = new StringBuilder("<html>");
			if (position > 0) {
				text.append("...");
			}
			if (position < result.start) {
				int readStart = position;
				while (position < result.start) {
					int read = reader.read(buffer, position - readStart, result.start - position);
					if (read < 0) {
						return "*Content changed* " + result.value;
					}
					position += read;
				}
				appendHtml(text, new String(buffer, 0, position - readStart));
			}
			text.append("<b><font color=\"red\">");
			appendHtml(text, result.value);
			text.append("</font></b>");
			while (position < result.start + result.value.length()) {
				int skipped = (int) reader.skip(result.start + result.value.length() - position);
				if (skipped == 0) {
					if (reader.read() < 0) {
						return "*Content changed* " + result.value;
					}
					position++;
				}
				position += skipped;
			}
			int more = 0;
			while (more < 100) {
				int read = reader.read(buffer, more, 100 - more);
				if (read < 0) {
					break;
				}
				more += read;
			}
			appendHtml(text, new String(buffer, 0, more));
			if (reader.read() >= 0) {
				text.append("...");
			}
			return text.toString();
		} catch (IOException e) {
			return "*Could not re-read* " + result.value;
		}
	}

	private void appendHtml(StringBuilder html, String content) {
		for (int c = 0; c < content.length(); c++) {
			switch (content.charAt(c)) {
			case '<':
				html.append("&lt;");
				break;
			case ' ':
				html.append("&nbsp;");
				break;
			case '\t':
				html.append("&nbsp;&nbsp;&nbsp;");
				break;
			case '\n':
				html.append("<br>");
				break;
			default:
				html.append(content.charAt(c));
				break;
			}
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
		configPanel
				.addHPanel("Search In:", "box",
						rootPanel -> rootPanel.fill()//
								.addTextField(null, theSearchBase, theFileFormat, tf -> tf.fill())//
								.addFileField(null, theSearchBase.map(FileUtils::asFile, f -> BetterFile.at(theFileSource, f.getPath())),
										true, null))//
				.spacer(3).addLabel(null, ObservableValue.of("----File Name----"), Format.TEXT, x -> x.fill())//
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
				.spacer(3).addLabel(null, ObservableValue.of("----File Content----"), Format.TEXT, x -> x.fill())//
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
				.spacer(3).addLabel(null, ObservableValue.of("----Excluded File Names---"), Format.TEXT, x -> x.fill())//
				.addTable((ObservableCollection<PatternConfig>) theExclusionPatterns.getValues(), exclTable -> {
					exclTable.withColumn("Pattern", String.class, PatternConfig::getPattern,
							c -> c.withWidths(100, 150, 500).withMutation(m -> m.asText(PATTERN_FORMAT)));
					exclTable.withColumn("Case", boolean.class, PatternConfig::isCaseSensitive,
							c -> c.withHeaderTooltip("Case-sensitive")
									.withRenderer(ObservableCellRenderer.checkRenderer(cell -> cell.getCellValue()))
									.withMutation(m -> m.asCheck()));
					exclTable.withAdd(() -> theExclusionPatterns.create().create().get(), null);
					exclTable.withRemove(null, null);
				})//
				.spacer(3).addLabel(null, ObservableValue.of("----File Metadata----"), Format.TEXT, x -> x.fill())//
				.addTextField("Max Zip Depth:", theZipLevel, SpinnerFormat.INT, tf -> tf.fill())//
				.addRadioField("Readable:", theBooleanAttributes.observe(FileBooleanAttribute.Readable),
						FileAttributeRequirement.values(), null)//
				.addRadioField("Writable:", theBooleanAttributes.observe(FileBooleanAttribute.Readable),
						FileAttributeRequirement.values(), null)//
				.addRadioField("Directory:", theBooleanAttributes.observe(FileBooleanAttribute.Readable),
						FileAttributeRequirement.values(), null)//
				.addRadioField("Hidden:", theBooleanAttributes.observe(FileBooleanAttribute.Readable),
						FileAttributeRequirement.values(), null)//
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
		// .addHPanel("Test Pattern:", "box", testFP->testFP//
		// .addTextField(null, theFileNameTest
		// .addHPanel("Text Pattern:", "box", textPanel->textPanel//
		// .addTextField(null, theFileContentPattern, PATTERN_FORMAT
		;
	}

	private void populateResultFiles(PanelPopulation.PanelPopulator<?, ?> configPanel) {
		configPanel.addTree(theResults, node -> node.children,
				tree -> tree.fill().fillV().withSelection(theSelectedResult, r -> r.parent, true)//
						.renderWith(node -> node.file.getName())//
						.withLeafTest(node -> !node.file.isDirectory()));
	}

	private void populateResultContent(PanelPopulation.PanelPopulator<?, ?> configPanel) {
		configPanel.addSplit(true, split -> split.visibleWhen(theFileContentPattern.map(v -> v != null)).fill().withSplitProportion(.5)
				.firstV(split1 -> split1.addTable(
						ObservableCollection.flattenValue(theSelectedResult.map(result -> result == null ? null : result.textResults)),
						list -> list.fill().visibleWhen(theFileContentPattern.map(p -> p != null))
								.withSelection(theSelectedTextResult, true).withColumn("Value", String.class, tr -> tr.value,
										c -> c.withWidths(100, 250, 1000))))//
				.lastV(split2 -> split2.addTextArea(null, SettableValue.asSettable(theSelectedRenderedText, __ -> null), Format.TEXT,
						tf -> tf.fill().fillV().modifyEditor(tf2 -> tf2.asHtml().setEditable(false)))))//
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
