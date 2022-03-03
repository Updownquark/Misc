package org.quark.searcher;

import java.awt.EventQueue;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.observe.ObservableAction;
import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.assoc.ObservableMap;
import org.observe.collect.ObservableCollection;
import org.observe.collect.ObservableSortedSet;
import org.observe.util.ModelTypes;
import org.observe.util.ObservableModelQonfigParser;
import org.observe.util.ObservableModelSet;
import org.observe.util.TypeTokens;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.QuickSwingParser;
import org.observe.util.swing.QuickSwingParser.QuickUiDef;
import org.qommons.QommonsUtils;
import org.qommons.QommonsUtils.NamedGroupCapture;
import org.qommons.StringUtils;
import org.qommons.collect.ElementId;
import org.qommons.config.DefaultQonfigParser;
import org.qommons.config.QonfigInterpreter;
import org.qommons.config.QonfigInterpreter.QonfigInterpretationException;
import org.qommons.config.QonfigParseException;
import org.qommons.io.BetterFile;
import org.qommons.io.BetterFile.FileBooleanAttribute;
import org.qommons.threading.QommonsTimer;

public class QuickSearcher {
	public static final double DEFAULT_MAX_SIZE = 1024L * 1024 * 1024 * 1024 * 1024; // 1 Petabyte

	public static class SearchResultNode {
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
					.build();
			textResults = ObservableCollection.build(TextResult.class).build();
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

		public BetterFile getFile() {
			return file;
		}

		public SearchResultNode getParent() {
			return parent;
		}

		public ObservableSortedSet<SearchResultNode> getChildren() {
			return children;
		}

		public ObservableCollection<TextResult> getTextResults() {
			return textResults;
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

		public SearchResultNode getFileResult() {
			return fileResult;
		}

		public long getPosition() {
			return position;
		}

		public long getLineNumber() {
			return lineNumber;
		}

		public long getColumnNumber() {
			return columnNumber;
		}

		public String getValue() {
			return value;
		}

		public Map<String, NamedGroupCapture> getCaptures() {
			return captures;
		}

		@Override
		public String toString() {
			return value;
		}
	}

	public enum SearchStatus {
		Idle, Searching, Canceling
	}

	public static void main(String... args) {
		String workingDir = System.getProperty("user.dir");
		EventQueue.invokeLater(() -> new QuickSearcher(workingDir));
	}

	private final SettableValue<SearchResultNode> theResults;
	private final SettableValue<SearchStatus> theStatus;
	private final SettableValue<String> theStatusMessage;
	private final List<Pattern> theDynamicExclusionPatterns;

	private final ObservableValue<BetterFile> theSearchBase;
	private final ObservableValue<Pattern> theFileNamePattern;
	private final ObservableValue<Pattern> theFileContentPattern;
	private final ObservableValue<Boolean> isSearchingMultipleContentMatches;
	private final ObservableMap<FileBooleanAttribute, FileAttributeRequirement> theFileRequirements;
	private final ObservableValue<Integer> theMaxFileMatchLength;
	private final ObservableValue<Long> theMinSize;
	private final ObservableValue<Long> theMaxSize;
	private final ObservableValue<Long> theMinTime;
	private final ObservableValue<Long> theMaxTime;
	private final SettableValue<SearchResultNode> theSelectedResult;
	
	private final QommonsTimer.TaskHandle theStatusUpdateHandle;
	
	private BetterFile theCurrentSearch;
	private boolean isSearching;
	private boolean isCanceling;

	public QuickSearcher(String workingDir) {
		theResults = SettableValue.build(SearchResultNode.class).build();
		theStatus = SettableValue.build(SearchStatus.class).withValue(SearchStatus.Idle).build();
		theStatusMessage = SettableValue.build(String.class).withValue("Ready to search").build();
		ObservableModelSet.ExternalModelSet extModels;
		try {
			extModels = ObservableModelSet.buildExternal()
					.withSubModel("ext", sub -> sub
							.with("workingDir", ModelTypes.Value.forType(String.class),
									ObservableModelQonfigParser.literal(workingDir, "\"" + workingDir + "\""))//
							.with("resultRoot", ModelTypes.Value.forType(SearchResultNode.class), theResults)//
							.with("status", ModelTypes.Value.forType(SearchStatus.class), theStatus)//
							.with("statusText", ModelTypes.Value.forType(String.class), theStatusMessage)//
							.with("searchAction", ModelTypes.Action.forType(Void.class), ObservableAction.of(TypeTokens.get().VOID, __ -> {
								QommonsTimer.getCommonInstance().offload(QuickSearcher.this::doSearch);
								return null;
							}).disableWith(//
									theStatus.map(status -> status == SearchStatus.Canceling ? "Canceling..please wait" : null)))//
					).build();
		} catch (QonfigInterpretationException e) {
			throw new IllegalStateException("Bad application configuration", e);
		}
		
		theStatusUpdateHandle = QommonsTimer.getCommonInstance().build(this::updateStatus, Duration.ofMillis(100), false).onEDT();
		updateStatus();
		
		try {
			URL searcherFile = QuickSearcher.class.getResource("qommons-searcher.qml");
			QuickSwingParser.QuickDocument doc = new QuickSwingParser().configureInterpreter(QonfigInterpreter.build(//
					ObservableModelQonfigParser.TOOLKIT.get(), //
					QuickSwingParser.BASE.get(), //
					QuickSwingParser.SWING.get())//
			).build()//
					.interpret(//
							new DefaultQonfigParser()//
									.withToolkit(ObservableModelQonfigParser.TOOLKIT.get(), //
											QuickSwingParser.BASE.get(), //
											QuickSwingParser.SWING.get())//
									.parseDocument(searcherFile.toString(), searcherFile.openStream())
									.getRoot(),
							QuickSwingParser.QuickDocument.class);
			QuickUiDef ui = doc.createUI(extModels);
			theSearchBase = doc.getHead().getModels().get("config.searchBase", ModelTypes.Value.forType(BetterFile.class))
					.get(ui.getModels());
			theFileNamePattern = doc.getHead().getModels().get("config.fileNamePattern", ModelTypes.Value.forType(Pattern.class))
					.get(ui.getModels());
			theFileContentPattern = doc.getHead().getModels().get("config.fileTextPattern", ModelTypes.Value.forType(Pattern.class))
					.get(ui.getModels());
			isSearchingMultipleContentMatches = doc.getHead().getModels()
					.get("config.multiContentMatches", ModelTypes.Value.forType(Boolean.class)).get(ui.getModels());
			theFileRequirements = doc.getHead().getModels()
					.get("config.fileRequirements", ModelTypes.Map.forType(FileBooleanAttribute.class, FileAttributeRequirement.class))
					.get(ui.getModels());
			theMaxFileMatchLength = doc.getHead().getModels().get("config.maxFileMatchLength", ModelTypes.Value.forType(int.class))
					.get(ui.getModels());
			theDynamicExclusionPatterns = doc.getHead().getModels()
					.get("config.excludedFileNames", ModelTypes.Collection.forType(Pattern.class)).get(ui.getModels());
			theMinSize = doc.getHead().getModels().get("config.minSize", ModelTypes.Value.forType(double.class)).get(ui.getModels())
					.map(d -> Math.round(d));
			theMaxSize = doc.getHead().getModels().get("config.maxSize", ModelTypes.Value.forType(double.class)).get(ui.getModels())
					.map(d -> Math.round(d));
			theMinTime = doc.getHead().getModels().get("config.minLM", ModelTypes.Value.forType(Instant.class)).get(ui.getModels())
					.map(Instant::toEpochMilli);
			theMaxTime = doc.getHead().getModels().get("config.maxLM", ModelTypes.Value.forType(Instant.class)).get(ui.getModels())
					.map(Instant::toEpochMilli);
			theSelectedResult = doc.getHead().getModels().get("app.selectedResult", ModelTypes.Value.forType(SearchResultNode.class))
					.get(ui.getModels());
			ui.createFrame().setVisible(true);
		} catch (IOException | QonfigParseException | QonfigInterpretationException | IllegalArgumentException e) {
			throw new IllegalStateException(e);
		}
	}

	private void doSearch() {
		if(theStatus.get()!=SearchStatus.Idle) {
			isCanceling=true;
			return;
		}
		
		isSearching=true;
		BetterFile file = theSearchBase.get();
		Pattern filePattern = theFileNamePattern.get();
		Pattern contentPattern = theFileContentPattern.get();
		SearchResultNode rootResult = new SearchResultNode(null, file);
		ObservableSwingUtils.onEQ(() -> {
			theStatus.set(SearchStatus.Searching, null);
			theResults.set(rootResult, null);
		});
		long start = System.currentTimeMillis();
		boolean succeeded = false;
		int[] searched = new int[2];
		try {
			doSearch(file, filePattern, contentPattern, isSearchingMultipleContentMatches.get(), //
					theFileRequirements, () -> rootResult, new StringBuilder(),
					new FileContentSeq(theMaxFileMatchLength.get()), false, searched);
			succeeded = true;
		} finally {
			isSearching=false;
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
							+ searched[1] + " director" + (searched[1] == 1 ? "y" : "ies")//
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

	public static String renderTextResult(TextResult result) {
	    if(result==null) {
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
		if (theSearchBase == null) {
			return "Not initialized";
		} else if (!theSearchBase.get().exists()) {
			return theSearchBase.get() + " does not exist";
		}
		return null;
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

	public static String isConfigurable(SearchStatus status) {
		if (status == SearchStatus.Idle) {
			return null;
		} else {
			return "Searching...";
		}
	}

	public static boolean isSearchClickable(SearchStatus status) {
		return status != SearchStatus.Canceling;
	}

	public static String getSearchText(SearchStatus status) {
		switch (status) {
		case Idle:
			return "Search";
		case Searching:
			return "Cancel";
		case Canceling:
			return "Canceling";
		}
		throw new IllegalStateException();
	}
}
