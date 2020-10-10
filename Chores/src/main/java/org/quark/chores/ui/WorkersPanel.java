package org.quark.chores.ui;

import java.awt.Color;
import java.awt.EventQueue;
import java.time.Instant;

import javax.swing.JPanel;

import org.observe.SettableValue;
import org.observe.collect.ObservableCollection;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.ObservableCellRenderer;
import org.observe.util.swing.PanelPopulation.PanelPopulator;
import org.observe.util.swing.PanelPopulation.TableBuilder;
import org.qommons.StringUtils;
import org.qommons.io.Format;
import org.qommons.io.SpinnerFormat;
import org.quark.chores.entities.AssignedJob;
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
									.withColumn("Excess Points", long.class, Worker::getExcessPoints, col -> {
										col.withHeaderTooltip("The number of points the worker has accumulated beyond expectations");
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
						.lastV(this::populateWorkerEditor));
	}

	void populateWorkerEditor(PanelPopulator<?, ?> bottom) {
		ObservableCollection<AssignedJob> allAssignments = ObservableCollection
				.flattenValue(theUI.getSelectedAssignment().map(assn -> assn == null ? null : assn.getAssignments().getValues()));
		ObservableCollection<AssignedJob> assignments = allAssignments.flow().refresh(theUI.getSelectedWorker().noInitChanges())
				.filter(assn -> assn.getWorker() == theUI.getSelectedWorker().get() ? null : "Wrong worker").collect();
		ObservableCollection<Job> availableJobs = theUI.getJobs().getValues().flow().refresh(theUI.getSelectedWorker().noInitChanges())//
				.whereContained(allAssignments.flow().map(Job.class, AssignedJob::getJob), false)//
				.filter(job -> {
					Worker worker = theUI.getSelectedWorker().get();
					if (worker == null) {
						return "No selected worker";
					} else if (!StatusPanel.shouldDo(worker, job, 1_000_000)) {
						return "Illegal assignment";
					} else {
						return null;
					}
				}).collect();
		SettableValue<Job> addJob = SettableValue.build(Job.class).safe(false).build();
		bottom.visibleWhen(theUI.getSelectedWorker().map(w -> w != null))
				.addHPanel(null, //
						new JustifiedBoxLayout(false).mainJustified().crossJustified(),
						split2 -> split2.fill().fillV()//
								.addVPanel(leftPanel -> leftPanel.decorate(deco -> deco.withTitledBorder("Job Preferences", Color.black))//
										.addTable(theUI.getJobs().getValues().flow().refresh(theUI.getSelectedWorker().noInitChanges())
												.collect(), this::configurePreferenceTable))//
								.addVPanel(
										rightPanel -> rightPanel.decorate(deco -> deco.withTitledBorder("Current Assignments", Color.black))//
												.addTable(assignments, this::configureAssignmentTable)//
												.addComboField("Add Assignment:", addJob, availableJobs,
														combo -> combo.renderWith(ObservableCellRenderer
																.<Job, Job> formatted(job -> job == null ? "" : job.getName())//
																.decorate((cell, deco) -> {
																	if (cell.getModelValue() == null) {
																		return;
																	}
																	Instant lastDone = cell.getModelValue().getLastDone();
																	if (lastDone == null) {
																		deco.withForeground(Color.black);
																	} else {
																		Instant due = lastDone.plus(cell.getModelValue().getFrequency());
																		if (due.compareTo(Instant.now()) <= 0) {
																			deco.withForeground(Color.black);
																		} else {
																			deco.withForeground(Color.gray);
																		}
																	}
																}))//
																.withValueTooltip(job -> {
																	Instant lastDone = job.getLastDone();
																	if (lastDone == null) {
																		return "Never done";
																	}
																	Instant due = lastDone.plus(job.getFrequency());
																	return "Due " + ChoreUtils.DATE_FORMAT.format(due);
																}))//
						));
		addJob.noInitChanges().act(evt -> {
			if (evt.getNewValue() == null) {
				return;
			}
			theUI.getSelectedAssignment().get().getAssignments().create()//
					.with(AssignedJob::getWorker, theUI.getSelectedWorker().get())//
					.with(AssignedJob::getJob, evt.getNewValue())//
					.create();
			EventQueue.invokeLater(() -> addJob.set(null, null));
		});
	}

	void configurePreferenceTable(TableBuilder<Job, ?> prefTable) {
		prefTable.fill().fillV().withColumn("Job", String.class, Job::getName, col -> col.withWidths(50, 150, 250))//
				.withColumn("Preference", int.class,
						job -> {
							return theUI.getSelectedWorker().get().getJobPreferences().getOrDefault(job, ChoreUtils.DEFAULT_PREFERENCE);
						},
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
	}

	void configureAssignmentTable(TableBuilder<AssignedJob, ?> table) {
		table.fill().fillV()//
				.withItemName("assignment").withNameColumn(assn -> assn.getJob().getName(), null, false, col -> {
					col.setName("Job").withWidths(50, 150, 250).decorate((cell, deco) -> {
						Color borderColor;
						if (cell.getModelValue().getCompletion() == 0) {
							borderColor = Color.red;
						} else if (cell.getModelValue().getCompletion() < cell.getModelValue().getJob().getDifficulty()) {
							borderColor = Color.yellow;
						} else {
							borderColor = Color.green;
						}
						deco.withLineBorder(borderColor, 2, false);
					});
				})//
				.withColumn("Difficulty", int.class, assn -> assn.getJob().getDifficulty(), null)//
				.withColumn("Complete", int.class, assn -> assn.getCompletion(), col -> col.withMutation(mut -> {
					mut.mutateAttribute((assn, complete) -> assn.setCompletion(complete))//
							.filterAccept((entry, completion) -> {
								if (completion < 0) {
									return "Completion cannot be negative";
								} else if (completion > entry.get().getJob().getDifficulty()) {
									return "Max completion is the difficulty of the job (" + entry.get().getJob().getDifficulty() + ")";
								} else {
									return null;
								}
							}).withRowUpdate(true).asText(SpinnerFormat.INT).clicks(1);
				})//
				)//
				.withRemove(jobs -> {
					theUI.getSelectedAssignment().get().getAssignments().getValues().removeAll(jobs);
				}, removeAction -> {
					removeAction.confirmForItems("Delete Assignment(s)?", "Are you sure you want to delete ", "?", true);
				});

	}
}
