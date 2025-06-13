package org.quark.file.browser;

import java.awt.Cursor;
import java.awt.EventQueue;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.observe.ObservableValue;
import org.observe.ObservableValueEvent;
import org.observe.Observer;
import org.observe.SettableValue;
import org.observe.collect.DataControlledCollection;
import org.observe.collect.ObservableCollection;
import org.observe.collect.ObservableSortedCollection;
import org.observe.file.ObservableFile;
import org.observe.util.swing.*;
import org.qommons.LambdaUtils;
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
	private final ObservableFile theWorkingDir;

	private final SettableValue<ObservableFile> theFile;
	private final ObservableValue<Boolean> isRefreshing;
	private final DataControlledCollection<ObservableFile, ?> theRoots;
	private final ObservableValue<DataControlledCollection<? extends ObservableFile, ?>> theCurrentContent;
	private final ObservableSortedCollection<ObservableFile> theContent;

	public BetterFileBrowser(FileDataSource dataSource, ObservableFile workingDir) {
		theDataSource = dataSource;
		theWorkingDir = workingDir;
		theFile = SettableValue.<ObservableFile> build().withValue(ObservableFile.observe(theWorkingDir)).build();
		theRoots = ObservableFile.getRoots(dataSource);
		theCurrentContent = theFile.map(f -> f == null ? theRoots : f.listFiles());
		theContent = ObservableCollection.flattenValue(theCurrentContent).flow().sorted(BetterFile.DISTINCT_NUMBER_TOLERANT).collect();
		isRefreshing = ObservableValue.flatten(theCurrentContent.<ObservableValue<Boolean>> map(c -> c.isRefreshing()));

		ObservableSwingUtils.onEQ(this::initComponents);
	}

	public SettableValue<ObservableFile> getFile() {
		return theFile;
	}

	private void initComponents() {
		// Table, up, file path
		SettableValue<ObservableFile> selectedFile = SettableValue.<ObservableFile> build().build();
		PanelPopulation.populateVPanel(this, null)//
		.addHPanel(null, new JustifiedBoxLayout(false).mainJustified(), p -> {
			p.fill().addTextField(null, theFile, new ObservableFile.FileFormat(theDataSource, theWorkingDir, true),
					tf -> tf.fill().modifyEditor(tf2 -> tf2.setReformatOnCommit(true)))//
			.addButton("..", this::navigateUp, btn -> btn.disableWith(theFile.map(f -> f == null ? "No parent" : null)));
		}).addSplit(true, split -> split.fill().fillV().withSplitProportion(0.5)//
				.firstV(splitTop -> splitTop.fill().fillV().addTable(theContent, table -> {
					table.fill().fillV().withNameColumn(BetterFile::getName, null, true, col -> col.withWidths(50, 200, 10000)
							.withMouseListener(new CategoryRenderStrategy.CategoryMouseAdapter<ObservableFile, String>() {
								@Override
								public void mouseClicked(ModelCell<? extends ObservableFile, ? extends String> cell, MouseEvent e) {
									if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2
											&& cell.getModelValue().isDirectory()) {
										theFile.set(cell.getModelValue(), e);
									}
								}
							}))//
					.withColumn("Last Modified", Instant.class, f -> {
						long lastMod = f.getLastModified();
						return lastMod == 0 ? Instant.ofEpochMilli(lastMod) : null;
					}, col -> col.withWidths(50, 130, 200)
							.withMutation(m -> m.asText(SpinnerFormat.flexDate(Instant::now, "EEE d MMM yyyy", null))))//
					.withColumn("Size", long.class, BetterFile::length,
							col -> col.withWidths(20, 50, 100).formatText(sz -> sz < 0 ? "?" : SIZE_FORMAT.format(sz * 1.0))//
							.withValueTooltip((f, sz) -> Format.LONG.withGroupingSeparator(',').format(sz)))//
					.withSelection(selectedFile, false);
				})).lastV(splitBottom -> splitBottom.fill().fillV().addComponent(null, new FileContentViewer(selectedFile),
						f -> f.fill().fillV())));
		isRefreshing.changes().act(new Observer.SimpleObserver<ObservableValueEvent<Boolean>>() {
			// We don't want to flash the wait cursor every second for trivial refreshes,
			// but if refresh takes a while, tell the user about it
			private volatile boolean isRefreshingNow;

			@Override
			public void onNext(ObservableValueEvent<Boolean> evt) {
				isRefreshingNow = evt.getNewValue();
				if (isRefreshingNow) {
					QommonsTimer.getCommonInstance()
					.build(LambdaUtils.printableRunnable(this::checkCursor, "checkCursor", null), null, false)
					.runNextIn(Duration.ofMillis(100));
				} else {
					setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
				}
			}

			private void checkCursor() {
				if (isRefreshingNow) {
					EventQueue.invokeLater(
							() -> setCursor(Cursor.getPredefinedCursor(isRefreshingNow ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR)));
				}
			}
		});
	}

	void navigateUp(Object cause) {
		ObservableFile file = theFile.get();
		if (file == null) {
			return;
		}
		theFile.set(file.getParent(), cause);
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
