package org.quark.file.browser;

import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.collect.ObservableCollection;
import org.observe.collect.ObservableSortedCollection;
import org.observe.util.swing.CategoryRenderStrategy;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.ModelCell;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.PanelPopulation;
import org.observe.util.swing.WindowPopulation;
import org.qommons.QommonsUtils;
import org.qommons.collect.CollectionElement;
import org.qommons.collect.CollectionUtils;
import org.qommons.collect.ElementId;
import org.qommons.io.ArchiveEnabledFileSource;
import org.qommons.io.BetterFile;
import org.qommons.io.BetterFile.FileDataSource;
import org.qommons.io.Format;
import org.qommons.io.NativeFileSource;
import org.qommons.io.SpinnerFormat;
import org.qommons.threading.QommonsTimer;

public class BetterFileBrowser extends JPanel {
	public static final Format<Double> SIZE_FORMAT = Format.doubleFormat(3)//
			.withUnit("B", true)//
			.withPrefix("k", 3).withPrefix("M", 6).withPrefix("G", 9).withPrefix("T", 12)//
			.withPrefix("P", 15).withPrefix("E", 18).withPrefix("Z", 21).withPrefix("Y", 24)//
			.build();

	private final FileDataSource theDataSource;
	private final BetterFile theWorkingDir;

	private final SettableValue<BetterFile> theFile;
	private final SettableValue<Boolean> isRefreshing;
	private final ObservableSortedCollection<BetterFile> theChildren;
	private final SettableValue<Boolean> isCurrentlyRefreshing;
	private final List<List<BetterFile>> theAncestorChildren;
	private int theAncestorIndex;
	private boolean isNavToParent;
	private boolean isNavToChild;

	public BetterFileBrowser(FileDataSource dataSource, BetterFile workingDir) {
		theDataSource = dataSource;
		theWorkingDir = workingDir;
		theFile = SettableValue.build(BetterFile.class).safe(false).withValue(theWorkingDir).build();
		theChildren = ObservableCollection.build(BetterFile.class).safe(true).sortBy(BetterFile.DISTINCT_NUMBER_TOLERANT).build();
		isRefreshing = SettableValue.build(boolean.class).safe(false).withValue(false).build();
		isCurrentlyRefreshing = SettableValue.build(boolean.class).safe(false).withValue(false).build();
		theAncestorChildren = new ArrayList<>();
		theFile.changes().act(evt -> {
			if (isNavToChild) {
				theAncestorIndex++;
				theAncestorChildren.add(QommonsUtils.unmodifiableCopy(theChildren));
			} else if (isNavToParent) {
				theAncestorIndex--;
			} else {
				theAncestorIndex = 0;
				theAncestorChildren.clear();
			}

			boolean clear = evt.getNewValue() != evt.getOldValue();
			boolean parent = isNavToParent;
			QommonsTimer.getCommonInstance().offload(() -> refresh(parent, clear));
		});
		QommonsTimer.TaskHandle refreshHandle = QommonsTimer.getCommonInstance()
				.build(() -> refresh(false, false), Duration.ofSeconds(1), false)
				.onAnyThread();
		isRefreshing.changes().act(evt -> {
			refreshHandle.setActive(evt.getNewValue());
		});

		ObservableSwingUtils.onEQ(this::initComponents);
	}

	public SettableValue<BetterFile> getFile() {
		return theFile;
	}

	private void initComponents() {
		// Table, up, file path
		SettableValue<BetterFile> selectedFile = SettableValue.build(BetterFile.class).safe(false).build();
		ObservableValue<String> refreshing = isCurrentlyRefreshing.map(r -> r ? "Refreshing" : null);
		PanelPopulation.populateVPanel(this, null)//
				.addHPanel(null, new JustifiedBoxLayout(false).mainJustified(), p -> {
					p.fill().addTextField(null, theFile, new BetterFile.FileFormat(theDataSource, theWorkingDir, true),
							tf -> tf.fill().modifyEditor(tf2 -> tf2.setReformatOnCommit(true)))//
							.addButton("..", this::navigateUp,
									btn -> btn.disableWith(theFile.map(f -> f == null ? "No parent" : null)).disableWith(refreshing))
							.addCheckField("Refresh:", isRefreshing.disableWith(refreshing), null)//
					;
				}).addSplit(true, split -> split.fill().fillV().withSplitProportion(0.5)//
						.firstV(splitTop -> splitTop.fill().fillV().addTable(theChildren, table -> {
							table.fill().fillV().withNameColumn(BetterFile::getName, null, true, col -> col.withWidths(50, 200, 10000)
									.withMouseListener(new CategoryRenderStrategy.CategoryMouseAdapter<BetterFile, String>() {
										@Override
										public void mouseClicked(ModelCell<? extends BetterFile, ? extends String> cell, MouseEvent e) {
											if (isCurrentlyRefreshing.get() || !SwingUtilities.isLeftMouseButton(e)
													|| e.getClickCount() != 2) {
												return;
											}
											if (cell.getModelValue().isDirectory()) {
												isNavToChild = true;
												theFile.set(cell.getModelValue(), e);
												isNavToChild = false;
											}
										}
									}))//
									.withColumn("Last Modified", Instant.class, f -> Instant.ofEpochMilli(f.getLastModified()),
											col -> col.withWidths(50, 120, 200)
													.withMutation(m -> m.asText(
															SpinnerFormat.flexDate(Instant::now, "EEE d MMM yyyy", TimeZone.getDefault()))))//
									.withColumn("Size", long.class, BetterFile::length, col -> col.withWidths(20, 50, 100)
											.formatText(sz -> sz < 0 ? "?" : SIZE_FORMAT.format(sz * 1.0)))//
									.withSelection(selectedFile, false)
							;
						})).lastV(splitBottom -> splitBottom.fill().fillV().addComponent(null, new FileContentViewer(selectedFile),
								f -> f.fill().fillV())));
	}

	void navigateUp(Object cause) {
		BetterFile file = theFile.get();
		if (file == null) {
			return;
		}
		isNavToParent = true;
		theFile.set(file.getParent(), cause);
		isNavToParent = false;
	}

	void refresh(boolean parent, boolean clear) {
		if (isCurrentlyRefreshing.get()) {
			return;
		}
		ObservableSwingUtils.onEQ(() -> {
			isCurrentlyRefreshing.set(true, null);
		});
		try {
			if (theAncestorIndex < theAncestorChildren.size()) {
				theChildren.clear();
				List<BetterFile> parentChildren = theAncestorChildren.get(theAncestorIndex);
				if (parentChildren != null) {
					theChildren.addAll(parentChildren);
				}
			} else if (clear) {
				theChildren.clear();
			}

			Set<ElementId> notFound = new TreeSet<>();
			for (CollectionElement<? extends BetterFile> el : theChildren.elements()) {
				notFound.add(el.getElementId());
			}

			BetterFile file = null;
			BooleanSupplier canceled = () -> false;
			do {
				file = theFile.get();
				if (file == null) {
					for (BetterFile root : BetterFile.getRoots(theDataSource)) {
						adjust(root, notFound);
					}
				} else {
					file.discoverContents(f -> {
						adjust(f, notFound);
					}, canceled);
				}
			} while (file != theFile.get());
			for (ElementId el : notFound) {
				theChildren.mutableElement(el).remove();
			}
		} finally {
			ObservableSwingUtils.onEQ(() -> {
				isCurrentlyRefreshing.set(false, null);
			});
		}
	}

	private void adjust(BetterFile child, Set<ElementId> notFound) {
		boolean[] added = new boolean[1];
		CollectionElement<? extends BetterFile> childEl = theChildren.getOrAdd(child, null, null, false, () -> added[0] = true);
		if (!added[0]) {
			notFound.remove(childEl.getElementId());
		}
	}

	private void adjust(List<? extends BetterFile> children) {
		ObservableSwingUtils.onEQ(() -> {
			CollectionUtils.synchronize(theChildren, children, (f1, f2) -> f1.getName().equals(f2.getName())).simple(f -> f)
					.commonUses(true, true).addLast()//
					.adjust();
		});
	}

	public static void main(String[] args) {
		FileDataSource fileSource = new ArchiveEnabledFileSource(new NativeFileSource())//
				.withArchival(new ArchiveEnabledFileSource.ZipCompression())//
				.withArchival(new ArchiveEnabledFileSource.GZipCompression())//
				.withArchival(new ArchiveEnabledFileSource.TarArchival())//
		;
		BetterFile workingDir = args.length == 1 ? BetterFile.at(fileSource, args[0]) : null;
		BetterFileBrowser browser = new BetterFileBrowser(fileSource, workingDir);
		ObservableSwingUtils.systemLandF();
		WindowPopulation.populateWindow(null, null, true, true)//
				.withContent(browser)//
				.withTitle(browser.getFile().map(f -> f == null ? "Computer" : f.getPath()))//
				.withSize(800, 1000)//
				.run(null);
	}
}
