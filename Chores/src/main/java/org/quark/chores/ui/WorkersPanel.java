package org.quark.chores.ui;

import java.awt.Color;

import javax.swing.JPanel;

import org.observe.util.swing.PanelPopulation.PanelPopulator;
import org.qommons.StringUtils;
import org.qommons.io.Format;
import org.qommons.io.SpinnerFormat;
import org.quark.chores.entities.Job;
import org.quark.chores.entities.Worker;

public class WorkersPanel extends JPanel {
	private final ChoresUI theUI;

	public WorkersPanel(ChoresUI ui) {
		theUI = ui;
	}

	public void addPanel(PanelPopulator<?, ?> panel) {
		panel.addSplit(true,
				split -> split.fill().fillV()//
						.withSplitProportion(theUI.getConfig().asValue(double.class).at("workers-split")
								.withFormat(Format.doubleFormat("0.0#"), () -> .3).buildValue(null))//
						.firstV(top -> top.addTable(theUI.getWorkers().getValues(), table -> {
							table.fill().dragSourceRow(null).dragAcceptRow(null)// Re-orderable by drag
									.withNameColumn(Worker::getName, Worker::setName, true, null)//
									.withColumn("Level", int.class, Worker::getLevel,
											col -> col.withMutation(mut -> mut.mutateAttribute(Worker::setLevel).asText(SpinnerFormat.INT)))//
									.withColumn("Ability", int.class, Worker::getAbility,
											col -> col
													.withMutation(mut -> mut.mutateAttribute(Worker::setAbility).asText(SpinnerFormat.INT)))//
									.withColumn("Labels", ChoreUtils.LABEL_SET_TYPE, Worker::getLabels, col -> {
										col.formatText(ChoreUtils.LABEL_SET_FORMAT::format)
												.withMutation(mut -> mut.mutateAttribute((worker, labels) -> {
													worker.getLabels().retainAll(labels);
													worker.getLabels().addAll(labels);
												}).filterAccept((workerEl, label) -> {
													if (workerEl.get().getLabels().contains(label)) {
														return label + " is already included";
													}
													return null;
												}).asText(ChoreUtils.LABEL_SET_FORMAT));
									})//
									.withColumn("Behind", long.class, Worker::getExcessPoints, col -> {
										col.withHeaderTooltip("The number of points in debt the worker has accumulated");
										col.withMutation(mut -> mut.asText(SpinnerFormat.LONG).mutateAttribute(Worker::setExcessPoints));
									})//
									.withSelection(theUI.getSelectedWorker(), false)//
									.withAdd(() -> {
										return theUI.getWorkers().create()//
												.with(Worker::getName,
														StringUtils.getNewItemName(theUI.getWorkers().getValues(), Worker::getName,
																"Worker", StringUtils.PAREN_DUPLICATES))//
												.with(Worker::getAbility, 100)//
												.create().get();
									}, null)//
									.withRemove(null,
											action -> action.confirmForItems("Remove workers?", "Permanently delete ", "?", true));
						}))//
						.lastV(bottom -> bottom.visibleWhen(theUI.getSelectedWorker().map(w -> w != null))
								.decorate(deco -> deco.withTitledBorder("Job Preferences", Color.black))//
								.addTable(theUI.getJobs().getValues().flow().refresh(theUI.getSelectedWorker().noInitChanges()).collect(),
										prefTable -> {
											prefTable.fill().fillV()//
													.withColumn("Job", String.class, Job::getName, col -> col.withWidths(50, 150, 250))//
													.withColumn("Preference", int.class,
															job -> theUI.getSelectedWorker().get().getJobPreferences().getOrDefault(job,
																	ChoreUtils.DEFAULT_PREFERENCE),
															col -> col.withMutation(mut -> mut.mutateAttribute((job, pref) -> {
																theUI.getSelectedWorker().get().getJobPreferences().put(job, pref);
															}).asText(SpinnerFormat.validate(SpinnerFormat.INT, pref -> {
																if (pref < 0) {
																	return "No negative preferences";
																} else if (pref > 10) {
																	return "Preference must be between 0 and 10";
																}
																return null;
															})).clicks(1))//
											);
										})));
	}
}
