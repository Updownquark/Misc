package org.quark.searcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.collect.ObservableCollection;
import org.observe.collect.ObservableSortedSet;
import org.observe.util.TypeTokens;
import org.observe.util.swing.ObservableSwingUtils;
import org.qommons.QommonsUtils;
import org.qommons.StringUtils;
import org.qommons.ThreadConstraint;
import org.qommons.Transactable;
import org.qommons.collect.CollectionLockingStrategy;
import org.qommons.collect.ElementId;
import org.qommons.collect.RRWLockingStrategy;
import org.qommons.io.BetterFile;
import org.qommons.io.BetterFile.FileBooleanAttribute;
import org.qommons.io.BetterPattern;
import org.qommons.io.BetterPattern.Match;
import org.qommons.io.BetterPattern.Matcher;
import org.qommons.io.BetterPattern.NamedGroupCapture;
import org.qommons.threading.QommonsTimer;

/** Java business logic for the Quick-based search app */
public class QuickSearcher {
	/** The default max file size to populate if the user hasn't yet selected one */
	public static final double DEFAULT_MAX_SIZE = 1024L * 1024 * 1024 * 1024 * 1024; // 1 Petabyte

	/** Represents a file matching the search, or the parent of such a file */
	public static class SearchResultNode {
		/** Just for debugging */
		final int searchNumber;
		final SearchResultNode parent;
		ElementId parentChildId;
		final BetterFile file;
		Map<String, NamedGroupCapture> matchGroups;
		int fileMatches;
		int contentMatches;
		final CollectionLockingStrategy locking;
		final ObservableSortedSet<SearchResultNode> children;
		ObservableCollection<TextResult> textResults;

		SearchResultNode(int searchNumber, SearchResultNode parent, BetterFile file, CollectionLockingStrategy locking) {
			this.searchNumber = searchNumber;
			this.parent = parent;
			this.file = file;
			this.locking = locking;
			children = ObservableSortedSet.build(SearchResultNode.class,
				(r1, r2) -> StringUtils.compareNumberTolerant(r1.file.getName(), r2.file.getName(), true, true)).withLocking(locking)
				.build();
			textResults = ObservableCollection.build(TextResult.class).withLocking(locking).build();
		}

		SearchResultNode getChild(BetterFile child) {
			for (SearchResultNode ch : children) {
				if (ch.file.equals(child)) {
					return ch;
				}
			}
			SearchResultNode node = new SearchResultNode(searchNumber, this, child, locking);
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

		/** @return The matched file */
		public BetterFile getFile() {
			return file;
		}

		/** @return The parent node */
		public SearchResultNode getParent() {
			return parent;
		}

		/** @return This node's children */
		public ObservableSortedSet<SearchResultNode> getChildren() {
			return children;
		}

		/** @return The text matches in this file */
		public ObservableCollection<TextResult> getTextResults() {
			return textResults;
		}

		@Override
		public String toString() {
			return searchNumber + ": " + file.getName();
		}
	}

	/** Represents a text match in a file */
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

		/** @return The file that this match belongs to */
		public SearchResultNode getFileResult() {
			return fileResult;
		}

		/** @return The absolute character position in the file of the start of this match */
		public long getPosition() {
			return position;
		}

		/** @return The line number of the start of this match */
		public long getLineNumber() {
			return lineNumber;
		}

		/** @return The column number of the start of this match in the line */
		public long getColumnNumber() {
			return columnNumber;
		}

		/** @return The text of this match */
		public String getValue() {
			return value;
		}

		/** @return The named group captures of this match */
		public Map<String, NamedGroupCapture> getCaptures() {
			return captures;
		}

		@Override
		public String toString() {
			return value;
		}
	}

	/** Status of the search app */
	public enum SearchStatus {
		/** Not searching */
		Idle,
		/** Actively searching */
		Searching,
		/** User has asked the search to be canceled, but the cancellation is not complete */
		Canceling
	}

	private final Transactable theLocking;
	private final SettableValue<SearchResultNode> theResults;
	private final SettableValue<SearchStatus> theStatus;
	private final SettableValue<String> theStatusMessage;
	private final List<BetterPattern> theDynamicExclusionPatterns;

	private final ObservableValue<BetterFile> theSearchBase;
	private final ObservableValue<String> theFileNamePattern;
	private final ObservableValue<Long> theMinSize;
	private final ObservableValue<Long> theMaxSize;
	private final ObservableValue<Long> theMinTime;
	private final ObservableValue<Long> theMaxTime;
	private final SettableValue<SearchResultNode> theSelectedResult;

	private final QommonsTimer.TaskHandle theStatusUpdateHandle;

	private BetterFile theCurrentSearch;
	private boolean isCanceling;
	private int theSearchNumber;

	public QuickSearcher(ObservableValue<BetterFile> searchBase, ObservableValue<String> fileNamePattern, //
		ObservableCollection<PatternConfig> exclusions, ObservableValue<Double> minFileSize, ObservableValue<Double> maxFileSize,
		ObservableValue<Instant> minFileTime, ObservableValue<Instant> maxFileTime) {
		theLocking = Transactable.transactable(new ReentrantReadWriteLock(), this, ThreadConstraint.ANY);
		theResults = SettableValue.build(SearchResultNode.class).withLocking(theLocking).build();
		theSelectedResult = SettableValue.build(SearchResultNode.class).withLocking(theLocking).build();
		theStatus = SettableValue.build(SearchStatus.class).withLocking(theLocking).withValue(SearchStatus.Idle).build();
		theStatusMessage = SettableValue.build(String.class).withLocking(theLocking).withValue("Ready to search").build();

		theSearchBase = searchBase;
		theFileNamePattern = fileNamePattern;
		theMinSize = minFileSize.transform(long.class, tx -> tx.cache(true).map(Math::round));
		theMaxSize = maxFileSize.transform(long.class, tx -> tx.cache(true).map(Math::round));
		theMinTime = minFileTime.transform(long.class, tx -> tx.cache(true).map(Instant::toEpochMilli));
		theMaxTime = maxFileTime.transform(long.class, tx -> tx.cache(true).map(Instant::toEpochMilli));
		theDynamicExclusionPatterns = exclusions.flow().map(BetterPattern.class, config -> {
			if (config.getPattern() == null) {
				return null;
			} else if (config.isCaseSensitive()) {
				return BetterPattern.compile(config.getPattern());
			} else {
				return BetterPattern.compile(config.getPattern(), Pattern.CASE_INSENSITIVE);
			}
		}).collect();

		theStatusUpdateHandle = QommonsTimer.getCommonInstance().build(this::updateStatus, Duration.ofMillis(100), false).onEDT();
		updateStatus();
	}

	static class SearchResult {
		int filesSearched;
		int directoriesSearched;
		int matchingFiles;
		int textMatchedFiles;
		int textMatches;
	}

	public ObservableValue<SearchStatus> getStatus() {
		return theStatus.unsettable();
	}

	public ObservableValue<String> getStatusMessage() {
		return theStatusMessage.unsettable();
	}

	public ObservableValue<SearchResultNode> getResultRoot() {
		return theResults;
	}

	public SettableValue<SearchResultNode> getSelectedResult() {
		return theSelectedResult;
	}

	public ObservableValue<String> isConfigurable() {
		return theStatus.map(String.class, status -> status == SearchStatus.Idle ? null : "Searching...");
	}

	public ObservableValue<String> isSearchEnabled() {
		return theSearchBase.transform(String.class, tx -> tx//
			.combineWith(theFileNamePattern)//
			.combine((sb, fnp) -> {
				if (sb == null) {
					return "Choose a folder to search in";
				} else if (fnp == null || fnp.isEmpty()) {
					return "Select a valid file pattern";
				} else {
					return null;
				}
			})//
		);
	}

	public ObservableValue<String> isSearchUiEnabled() {
		return ObservableValue.firstValue(TypeTokens.get().STRING, s -> s != null, () -> null, //
			isSearchEnabled(), //
			theStatus.map(String.class, status -> {
				switch (status) {
				case Idle:
					return null;
				case Searching:
					return "Search in progress...";
				case Canceling:
					return "Canceling...";
				}
				return "?";
			})//
		);
	}

	public ObservableValue<String> isSearchActionEnabled() {
		return ObservableValue.firstValue(TypeTokens.get().STRING, s -> s != null, () -> null, //
			isSearchEnabled(), //
			theStatus.map(String.class, status -> {
				switch (status) {
				case Idle:
					return null;
				case Searching:
					return null; // Cancellable
				case Canceling:
					return "Canceling...";
				}
				return "?";
			})//
		);
	}

	/** @return The text to display for the search button */
	public ObservableValue<String> getSearchText() {
		return theStatus.map(String.class, status -> {
			switch (status) {
			case Idle:
				return "Search";
			case Searching:
				return "Cancel";
			case Canceling:
				return "Canceling";
			}
			return "?";
		});
	}

	public void search(BetterFile searchBase, //
		String fileNamePattern, boolean fileNameRegex, boolean fileNameCaseSensitive, //
		String fileContentPattern, boolean fileContentRegex, boolean fileContentCaseSensitive, //
		boolean searchMultipleContentMatches, int maxFileMatchLength,
		Map<FileBooleanAttribute, FileAttributeRequirement> fileRequirements) {
		QommonsTimer.getCommonInstance().offload(() -> {
			doSearch(searchBase, fileNamePattern, fileNameRegex, fileNameCaseSensitive, fileContentPattern, fileContentRegex,
				fileContentCaseSensitive, searchMultipleContentMatches, maxFileMatchLength, fileRequirements);
		});
	}

	private void doSearch(BetterFile searchBase, //
		String fileNamePattern, boolean fileNameRegex, boolean fileNameCaseSensitive, //
		String fileContentPattern, boolean fileContentRegex, boolean fileContentCaseSensitive, //
		boolean searchMultipleContentMatches, int maxFileMatchLength,
		Map<FileBooleanAttribute, FileAttributeRequirement> fileRequirements) {
		System.out.println("Search called");
		if (theStatus.get() != SearchStatus.Idle) {
			isCanceling = true;
			return;
		}

		BetterPattern filePattern;
		if (fileNamePattern == null || fileNamePattern.isEmpty()) {
			filePattern = null;
		} else if (!fileNameRegex) {
			filePattern = new BetterPattern.SimpleStringSearch(fileNamePattern, fileNameCaseSensitive, true);
		} else {
			filePattern = filePattern(fileNamePattern, fileNameCaseSensitive);
		}
		BetterPattern contentPattern;
		if (fileContentPattern == null || fileContentPattern.isEmpty()) {
			contentPattern = null;
		} else if (!fileContentRegex) {
			contentPattern = new BetterPattern.SimpleStringSearch(fileContentPattern, fileContentCaseSensitive, true);
		} else {
			contentPattern = BetterPattern.compile(fileContentPattern, fileContentCaseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
		}
		SearchResultNode rootResult = new SearchResultNode(++theSearchNumber, null, searchBase, new RRWLockingStrategy(theLocking));
		ObservableSwingUtils.onEQ(() -> {
			theStatus.set(SearchStatus.Searching, null);
			theResults.set(rootResult, null);
		});
		long start = System.currentTimeMillis();
		boolean succeeded = false;
		SearchResult result = new SearchResult();
		try {
			theStatusUpdateHandle.setActive(true);
			doSearch(searchBase, filePattern, contentPattern, searchMultipleContentMatches, //
				fileRequirements, () -> rootResult, new StringBuilder(), new FileContentSeq(maxFileMatchLength), new boolean[1],
				result);
			succeeded = true;
		} finally {
			long end = System.currentTimeMillis();
			theStatusUpdateHandle.setActive(false);
			theCurrentSearch = null;
			boolean succ = succeeded;
			ObservableSwingUtils.onEQ(() -> {
				theStatus.set(SearchStatus.Idle, null);
				boolean canceled = isCanceling;
				isCanceling = false;
				if (!succ) {
					theStatusMessage.set("Search failed after " + QommonsUtils.printTimeLength(end - start), null);
				} else if (canceled) {
					theStatusMessage.set("Canceled search after " + QommonsUtils.printTimeLength(end - start), null);
				} else {
					StringBuilder str = new StringBuilder("Found ");
					if (contentPattern != null) {
						str.append(result.textMatches).append(" match").append(result.textMatches == 1 ? "" : "es").append(" in ")
							.append(result.textMatchedFiles).append(" of ");
					}
					str.append(result.matchingFiles).append(" matching file").append(result.matchingFiles == 1 ? "" : "s")//
						.append(" among ").append(result.filesSearched).append(" file").append(result.filesSearched == 1 ? "" : "s")//
						.append(" and ").append(result.directoriesSearched).append(" director")
						.append(result.directoriesSearched == 1 ? "y" : "ies")//
						.append(" in ").append(QommonsUtils.printTimeLength(end - start));
					theStatusMessage.set(str.toString(), null);
				}
			});
		}
	}

	void doSearch(BetterFile file, BetterPattern filePattern, BetterPattern contentPattern, boolean searchMultiContent, //
		Map<FileBooleanAttribute, FileAttributeRequirement> booleanAtts, Supplier<SearchResultNode> nodeGetter, StringBuilder pathSeq,
		FileContentSeq contentSeq, boolean[] hasMatch, SearchResult result) {
		if (isCanceling) {
			return;
		}

		int prePathLen = pathSeq.length();
		pathSeq.append(file.getName());
		boolean dir = file.isDirectory();
		if (dir) {
			pathSeq.append('/');
		}
		theCurrentSearch = file;
		for (BetterPattern exclusion : theDynamicExclusionPatterns) {
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
		Match fileMatcher = testFileName(filePattern, pathSeq);
		if (isCanceling) {
			return;
		}
		if (filePattern == null || fileMatcher != null) {
			boolean matches = true;
			for (FileBooleanAttribute attr : FileBooleanAttribute.values()) {
				if (!booleanAtts.getOrDefault(attr, FileAttributeRequirement.Maybe).matches(file.get(attr))) {
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
				if (file.isDirectory()) {
					contentMatches = Collections.emptyList();
				} else {
					result.matchingFiles++;
					contentMatches = testFileContent(nodeGetter, contentPattern, file, searchMultiContent, contentSeq.clear());
					result.textMatches += contentMatches.size();
				}
				matches = !contentMatches.isEmpty();
				if (matches) {
					result.textMatchedFiles++;
				}
			} else {
				result.matchingFiles++;
				contentMatches = Collections.emptyList();
				matches = true;
			}
			if (isCanceling) {
				return;
			}
			if (matches) {
				node[0] = nodeGetter.get();
				node[0].matchGroups = filePattern == null ? Collections.emptyMap() : fileMatcher.getGroups();
				if (!contentMatches.isEmpty()) {
					node[0].textResults.addAll(contentMatches);
				}
				node[0].update(1, contentMatches.size());
				if (!hasMatch[0]) {
					hasMatch[0] = true;
					ObservableSwingUtils.onEQ(() -> {
						theSelectedResult.set(node[0], null);
					});
				}
			}
		}
		if (dir) {
			List<? extends BetterFile> children = file.listFiles();
			result.directoriesSearched++;
			for (BetterFile child : children) {
				if (isCanceling) {
					return;
				}
				doSearch(child, filePattern, contentPattern, searchMultiContent, booleanAtts, () -> {
					if (node[0] == null) {
						node[0] = nodeGetter.get();
					}
					return node[0].getChild(child);
				}, pathSeq, contentSeq, hasMatch, result);
			}
		} else {
			result.filesSearched++;
		}
		pathSeq.setLength(prePathLen);
	}

	private static BetterPattern filePattern(String filePatternStr, boolean caseSensitive) {
		filePatternStr = filePatternStr.replaceAll("//", "/.*/");
		return BetterPattern.compile(filePatternStr, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
	}

	private static Match testFileName(BetterPattern filePattern, CharSequence path) {
		if (filePattern == null) {
			return null;
		}
		if (path.toString().endsWith(".class") && path.toString().contains("swing")) {
			// BreakpointHere.breakpoint();
		}
		Match found;
		if (filePattern.toString().indexOf('/') >= 0) {
			// Otherwise, we need to include more of the path
			if (filePattern.toString().contains("/.*/")) {
				// Double-slash -- multi-path matcher. Need to try every path up to the root of the search
				found = null;
				for (int i = path.length() - 1; found == null && i >= 0; i--) {
					if (path.charAt(i) == '/') {
						Matcher matcher = filePattern.matcher(StringUtils.cheapSubSequence(path, i + 1, path.length()));
						found = matcher.matches();
					}
				}
				if (found == null) {
					Matcher matcher = filePattern.matcher(path);
					found = matcher.matches();
				}
			} else {
				int slashes = 1;
				for (int i = 0; i < filePattern.toString().length(); i++) {
					if (filePattern.toString().charAt(i) == '/') {
						slashes++;
					}
				}
				int targetIdx;
				for (targetIdx = path.length(); targetIdx > 0 && slashes > 0; targetIdx--) {
					if (path.charAt(targetIdx - 1) == '/') {
						slashes--;
					}
				}
				if (slashes == 0) {
					Matcher matcher = filePattern.matcher(StringUtils.cheapSubSequence(path, targetIdx + 1, path.length()));
					found = matcher.matches();
				} else {
					found = null;
				}
			}
		} else {
			// If the pattern does not explicitly accommodate directories, then we only match the terminal file.
			int lastSlash;
			for (lastSlash = path.length() - 1; lastSlash >= 0 && path.charAt(lastSlash) != '/'; lastSlash--) {}
			Matcher matcher = filePattern.matcher(StringUtils.cheapSubSequence(path, lastSlash + 1, path.length()));
			found = matcher.matches();
		}
		return found;
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

	private static List<TextResult> testFileContent(Supplier<SearchResultNode> fileResult, BetterPattern contentPattern, BetterFile file,
		boolean searchMulti, FileContentSeq seq) {
		List<TextResult> results = Collections.emptyList();
		try (Reader reader = new BufferedReader(new InputStreamReader(file.read()))) {
			while (seq.advance(reader, -1)) {
				Matcher m = contentPattern.matcher(seq);
				for (Match match = m.find(); match != null; match = m.find()) {
					if (match.getStart() > 0) {
						seq.advance(reader, match.getStart());
						m = contentPattern.matcher(seq);
						if ((match = m.lookingAt()) == null) {
							System.err.println("Lost a match?");
							continue;
						}
					}
					if (results.isEmpty()) {
						results = new ArrayList<>(searchMulti ? 1 : 5);
					}
					results.add(makeTextResult(fileResult.get(), match, seq.getPosition(), seq.getLine(), seq.getColumn()));
					if (!searchMulti) {
						break;
					}
					seq.advance(reader, match.getEnd());
					m = contentPattern.matcher(seq);
				}
				if (!results.isEmpty() && !searchMulti) {
					break;
				}
			}
		} catch (IOException e) {
			System.err.println(e);
		}
		return results;
	}

	private static TextResult makeTextResult(SearchResultNode fileResult, Match match, long position, long lineNumber, long columnNumber) {
		return new TextResult(fileResult, position, lineNumber, columnNumber, match.toString(), match.getGroups());
	}

	/**
	 * @param result The text result to render
	 * @return The HTML text to render for the user showing the text match in the context of its sub-section of the rest of the file
	 */
	public static String renderTextResult(TextResult result) {
		if (result == null) {
			return null;
		}
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
			boolean started = false, ended = false;
			while (appending) {
				long charPos = seq.getPosition();
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

	private void updateStatus() {
		String status;
		BetterFile f = theCurrentSearch;
		if (f != null) {
			status = f.getPath();
		} else if (theSearchBase.get() == null) {
			status = "No search root set";
		} else if (!theSearchBase.get().exists()) {
			status = theSearchBase.get() + " does not exist";
		} else {
			status = getIdleStatus();
		}
		theStatusMessage.set(status, null);
	}

	private String getIdleStatus() {
		if (theSearchBase == null || theSearchBase.get() == null) {
			return "Choose a folder to search in";
		} else if (theFileNamePattern.get() == null) {
			return "Select a valid file pattern";
		} else {
			return "Ready to search";
		}
	}
}
