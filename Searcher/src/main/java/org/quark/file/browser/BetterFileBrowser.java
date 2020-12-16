package org.quark.file.browser;

import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.TimeZone;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.collect.ObservableCollection;
import org.observe.collect.ObservableSortedCollection;
import org.observe.file.ObservableFile;
import org.observe.util.swing.CategoryRenderStrategy;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.ModelCell;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.PanelPopulation;
import org.observe.util.swing.WindowPopulation;
import org.qommons.io.ArchiveEnabledFileSource;
import org.qommons.io.BetterFile;
import org.qommons.io.BetterFile.FileDataSource;
import org.qommons.io.Format;
import org.qommons.io.NativeFileSource;
import org.qommons.io.SpinnerFormat;

public class BetterFileBrowser extends JPanel {
	public static final Format<Double> SIZE_FORMAT = Format.doubleFormat(3)//
			.withUnit("B", true)//
			.withPrefix("k", 3).withPrefix("M", 6).withPrefix("G", 9).withPrefix("T", 12)//
			.withPrefix("P", 15).withPrefix("E", 18).withPrefix("Z", 21).withPrefix("Y", 24)//
			.build();

	private final FileDataSource theDataSource;
	private final ObservableFile theWorkingDir;

	private final SettableValue<ObservableFile> theFile;
	private final SettableValue<Boolean> isRefreshing;
	private final ObservableSortedCollection<ObservableFile> theRoots;
	private final ObservableSortedCollection<ObservableFile> theChildren;
	private final SettableValue<Boolean> isCurrentlyRefreshing;
	private int theAncestorIndex;
	private boolean isNavToParent;
	private boolean isNavToChild;

	public BetterFileBrowser(FileDataSource dataSource, ObservableFile workingDir) {
		theDataSource = dataSource;
		theWorkingDir = workingDir;
		theFile = SettableValue.build(ObservableFile.class).safe(false).withValue(ObservableFile.observe(theWorkingDir)).build();
		theRoots = ObservableFile.getRoots(dataSource).flow().sorted(BetterFile.DISTINCT_NUMBER_TOLERANT).collect();
		theChildren = ObservableCollection.flattenValue(theFile.map(f -> f == null ? theRoots : f.listFiles())).flow()
				.sorted(BetterFile.DISTINCT_NUMBER_TOLERANT).collect();
		isRefreshing = SettableValue.build(boolean.class).safe(false).withValue(false).build();
		isCurrentlyRefreshing = SettableValue.build(boolean.class).safe(false).withValue(false).build();

		ObservableSwingUtils.onEQ(this::initComponents);
	}

	public SettableValue<ObservableFile> getFile() {
		return theFile;
	}

	private void initComponents() {
		// Table, up, file path
		SettableValue<ObservableFile> selectedFile = SettableValue.build(ObservableFile.class).safe(false).build();
		ObservableValue<String> refreshing = isCurrentlyRefreshing.map(r -> r ? "Refreshing" : null);
		PanelPopulation.populateVPanel(this, null)//
				.addHPanel(null, new JustifiedBoxLayout(false).mainJustified(), p -> {
					p.fill().addTextField(null, theFile, new ObservableFile.FileFormat(theDataSource, theWorkingDir, true),
							tf -> tf.fill().modifyEditor(tf2 -> tf2.setReformatOnCommit(true)))//
							.addButton("..", this::navigateUp,
									btn -> btn.disableWith(theFile.map(f -> f == null ? "No parent" : null)).disableWith(refreshing))
							.addCheckField("Refresh:", isRefreshing.disableWith(refreshing), null)//
					;
				}).addSplit(true, split -> split.fill().fillV().withSplitProportion(0.5)//
						.firstV(splitTop -> splitTop.fill().fillV().addTable(theChildren, table -> {
							table.fill().fillV().withNameColumn(BetterFile::getName, null, true, col -> col.withWidths(50, 200, 10000)
									.withMouseListener(new CategoryRenderStrategy.CategoryMouseAdapter<ObservableFile, String>() {
										@Override
										public void mouseClicked(ModelCell<? extends ObservableFile, ? extends String> cell, MouseEvent e) {
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
									.withColumn("Last Modified", Instant.class, f -> {
										long lastMod = f.getLastModified();
										return lastMod == 0 ? Instant.ofEpochMilli(lastMod) : null;
									}, col -> col.withWidths(50, 130, 200).withMutation(
											m -> m.asText(SpinnerFormat.flexDate(Instant::now, "EEE d MMM yyyy", TimeZone.getDefault()))))//
									.withColumn("Size", long.class, BetterFile::length,
											col -> col.withWidths(20, 50, 100)
													.formatText(sz -> sz < 0 ? "?" : SIZE_FORMAT.format(sz * 1.0)))//
									.withSelection(selectedFile, false);
						})).lastV(splitBottom -> splitBottom.fill().fillV().addComponent(null, new FileContentViewer(selectedFile),
								f -> f.fill().fillV())));
	}

	void navigateUp(Object cause) {
		ObservableFile file = theFile.get();
		if (file == null) {
			return;
		}
		isNavToParent = true;
		theFile.set(file.getParent(), cause);
		isNavToParent = false;
	}

	public static void main(String[] args) {
		FileDataSource fileSource = new ArchiveEnabledFileSource(new NativeFileSource())//
				.withArchival(new ArchiveEnabledFileSource.ZipCompression())//
				.withArchival(new ArchiveEnabledFileSource.GZipCompression())//
				.withArchival(new ArchiveEnabledFileSource.TarArchival())//
		;
		ObservableFile workingDir = args.length == 1 ? ObservableFile.observe(BetterFile.at(fileSource, args[0])) : null;
		BetterFileBrowser browser = new BetterFileBrowser(fileSource, workingDir);
		ObservableSwingUtils.systemLandF();
		WindowPopulation.populateWindow(null, null, true, true)//
				.withContent(browser)//
				.withTitle(browser.getFile().map(f -> f == null ? "Computer" : f.getPath()))//
				.withSize(800, 1000)//
				.run(null);
	}
}
