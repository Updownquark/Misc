package org.quark.chores.ui;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;

import org.observe.assoc.ObservableMultiMap;
import org.observe.collect.ObservableCollection;
import org.observe.util.TypeTokens;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.PanelPopulation.PanelPopulator;
import org.qommons.BiTuple;
import org.qommons.io.SpinnerFormat;
import org.quark.chores.entities.AssignedJob;
import org.quark.chores.entities.Assignment;
import org.quark.chores.entities.Job;
import org.quark.chores.entities.Worker;

public class StatusPanel extends JPanel {
	private final ChoresUI theUI;

	public StatusPanel(ChoresUI ui) {
		theUI = ui;
	}

	public void addPanel(PanelPopulator<?, ?> panel) {
		ObservableMultiMap<Worker, AssignedJob> assignmentsByWorker = ObservableCollection
				.flattenValue(//
						theUI.getSelectedAssignment().<ObservableCollection<AssignedJob>> transform(//
								TypeTokens.get().keyFor(ObservableCollection.class).parameterized(AssignedJob.class), //
								tx -> tx.nullToNull(true).map(asn -> asn.getAssignments().getValues())))//
				.flow().groupBy(TypeTokens.get().of(Worker.class), AssignedJob::getWorker, null).gather();
		panel.addLabel("Last Assignment:", theUI.getSelectedAssignment().transform(TypeTokens.get().of(Instant.class),
				tx -> tx.nullToNull(true).map(asn -> asn.getDate())), ChoreUtils.DATE_FORMAT, null);
		panel.addTable(assignmentsByWorker.observeSingleEntries(), table -> {
			table.fill().fillV()//
					.withColumn("Worker", String.class, entry -> entry.getKey().getName(), null)//
					.withColumn("Job", String.class, entry -> entry.getValue().getJob().getName(), col -> {
						col.withWidths(50, 150, 250).decorate((cell, deco) -> {
							Color borderColor;
							if (cell.getModelValue().get().getCompletion() == 0) {
								borderColor = Color.red;
							} else if (cell.getModelValue().get().getCompletion() < cell.getModelValue().get().getJob().getDifficulty()) {
								borderColor = Color.yellow;
							} else {
								borderColor = Color.green;
							}
							deco.withLineBorder(borderColor, 2, false);
						});
					})//
					.withColumn("Difficulty", int.class, entry -> entry.getValue().getJob().getDifficulty(), null)//
					.withColumn("Complete", int.class, entry -> entry.getValue().getCompletion(), col -> col.withMutation(mut -> {
						mut.mutateAttribute((entry, complete) -> entry.getValue().setCompletion(complete))//
								.filterAccept((entry, completion) -> {
									if (completion < 0) {
										return "Completion cannot be negative";
									} else if (completion > entry.get().getValue().getJob().getDifficulty()) {
										return "Max completion is the difficulty of the job ("
												+ entry.get().getValue().getJob().getDifficulty() + ")";
									} else {
										return null;
									}
								}).withRowUpdate(true).asText(SpinnerFormat.INT).clicks(1);
					}))//
			;
		});
		panel.addHPanel(null, new JustifiedBoxLayout(false).mainCenter(), p -> p.addButton("New Assignment",
				__ -> createNewAssignments(panel), btn -> btn.withTooltip("Creates a new set of assignments")));
	}

	private void createNewAssignments(PanelPopulator<?, ?> panel) {
		StringBuilder message = null;
		if (theUI.getSelectedAssignment().get() != null) {
			for (AssignedJob job : theUI.getSelectedAssignment().get().getAssignments().getValues()) {
				if (job.getCompletion() < job.getJob().getDifficulty()) {
					message = append(message, job.getWorker().getName() + ": " + job.getJob().getName() + " "
							+ ((int) Math.round(job.getCompletion() * 100.0 / job.getJob().getDifficulty())) + "% complete");
				}
			}
		}
		if (message != null) {
			message.append("\n\nIf a new assignment is created, the incomplete jobs will be counted against the workers.\n"
					+ "Are you sure you want to create a new assignment now?");
			if (!panel.alert("Some jobs have not been completed", message.toString()).confirm(true)) {
				return;
			}
		}
		Map<Worker, Long> excessPoints = new IdentityHashMap<>();
		if (theUI.getSelectedAssignment().get() != null) {
			for (Worker worker : theUI.getWorkers().getValues()) {
				excessPoints.put(worker, worker.getExcessPoints() - worker.getAbility());
			}
			for (AssignedJob job : theUI.getSelectedAssignment().get().getAssignments().getValues()) {
				int lacking = job.getJob().getDifficulty() - job.getCompletion();
				excessPoints.compute(job.getWorker(), (worker, excess) -> {
					// Cap the excess point deficit at the worker's ability--don't let the points pile up forever
					return Math.max(excess - lacking, -worker.getAbility());
				});
				if (lacking <= 0) {
					job.getJob().setLastDone(theUI.getSelectedAssignment().get().getDate());
				}
			}
			for (Map.Entry<Worker, Long> entry : excessPoints.entrySet()) {
				entry.getKey().setExcessPoints(entry.getValue());
			}
		} else {
			for (Worker worker : theUI.getWorkers().getValues()) {
				excessPoints.put(worker, worker.getExcessPoints());
			}
		}
		theUI.getAssignments().getValues().clear(); // For the moment, let's not care about history

		// Now create the new Assignment
		Assignment newAssignment = theUI.getAssignments().create()//
				.with(Assignment::getDate, Instant.now())//
				.create().get();
		List<Job> allJobs = new ArrayList<>(theUI.getJobs().getValues());
		{// Remove jobs that shouldn't be done yet
			Iterator<Job> jobIter = allJobs.iterator();
			while (jobIter.hasNext()) {
				Job job = jobIter.next();
				if (job.getLastDone() == null) {
					continue;
				} else if (job.getFrequency() == null
						|| job.getLastDone().plus(job.getFrequency()).compareTo(newAssignment.getDate()) > 0) {
					jobIter.remove();
				}
			}
		}
		if (allJobs.isEmpty()) {
			panel.alert("No Applicable Jobs", "None of the workers are available to do any jobs that need done").display();
			return;
		}
		Collections.sort(allJobs, (job1, job2) -> {
			if (job1.getLastDone() == null) {
				if (job2.getLastDone() == null) {
					return 0;
				}
				return 1;
			} else if (job2.getLastDone() == null) {
				return -1;
			}
			Instant todo1 = job1.getLastDone().plus(job1.getFrequency());
			Instant todo2 = job2.getLastDone().plus(job2.getFrequency());
			int comp = todo1.compareTo(todo2);
			if (comp == 0) {
				comp = job1.getFrequency().compareTo(job2.getFrequency());
			}
			return comp;
		});
		Map<Worker, BiTuple<Integer, Integer>> preferenceRanges = new IdentityHashMap<>();
		for (Worker worker : theUI.getWorkers().getValues()) {
			int minPref = worker.getJobPreferences().getOrDefault(allJobs.get(0), ChoreUtils.DEFAULT_PREFERENCE);
			int maxPref = minPref;
			for (Job job : allJobs) {
				int pref = worker.getJobPreferences().getOrDefault(job, ChoreUtils.DEFAULT_PREFERENCE);
				if (pref < minPref) {
					minPref = pref;
				} else if (pref > maxPref) {
					maxPref=pref;
				}
			}
			preferenceRanges.put(worker, new BiTuple<>(minPref, maxPref));
		}
		Map<Worker, Integer> workers = new IdentityHashMap<>();
		for (Map.Entry<Worker, Long> entry : excessPoints.entrySet()) {
			workers.put(entry.getKey(), (int) (entry.getKey().getAbility() - entry.getValue()));
		}
		List<Worker> jobWorkers = new ArrayList<>(theUI.getWorkers().getValues().size());
		for (Job job : allJobs) {
			// Assemble the workers that can do the job
			for (Worker worker : workers.keySet()) {
				if (shouldDo(worker, job, workers.get(worker))) {
					jobWorkers.add(worker);
				}
			}
			int workerIndex;
			if (jobWorkers.isEmpty()) {
				continue;
			} else if (jobWorkers.size() == 1) {
				workerIndex = 0;
			} else {
				int[] prefs = new int[jobWorkers.size()];
				int i = 0;
				// Scale the preferences in a scale of 1 to 10
				int totalPreference = 0;
				for (Worker worker : jobWorkers) {
					BiTuple<Integer, Integer> prefRange = preferenceRanges.get(worker);
					int pref = worker.getJobPreferences().getOrDefault(job, ChoreUtils.DEFAULT_PREFERENCE);
					if (prefRange.getValue1().equals(prefRange.getValue2())) {
						pref = 5;
					} else {
						pref = 1 + (int) ((pref - prefRange.getValue1()) * 1.0 / (prefRange.getValue2() - prefRange.getValue1()) * 10);
					}
					prefs[i++] = pref;
					totalPreference += pref;
				}
				int prefIndex = (int) (Math.random() * totalPreference);
				for (i = 0; i < jobWorkers.size(); i++) {
					prefIndex -= prefs[i];
					if (prefIndex < 0) {
						break;
					}
				}
				workerIndex = i;
			}
			Worker worker = jobWorkers.get(workerIndex);
			newAssignment.getAssignments().create()//
					.with(AssignedJob::getWorker, worker)//
					.with(AssignedJob::getJob, job)//
					.with(AssignedJob::getCompletion, 0)//
					.create();
			if (workers.compute(worker, (__, work) -> work - job.getDifficulty()) <= 0) {
				workers.remove(worker);
			}

			jobWorkers.clear();
		}
	}

	private static StringBuilder append(StringBuilder message, String err) {
		if (message == null) {
			message = new StringBuilder();
		} else {
			message.append("\n");
		}
		message.append(err);
		return message;
	}

	private static boolean shouldDo(Worker worker, Job job, int workLeft) {
		if (job.getDifficulty() > worker.getAbility()) {
			return false;
		} else if (job.getDifficulty() > workLeft * 2) {
			return false;
		}
		for (String label : worker.getLabels()) {
			if (job.getExclusionLabels().contains(label)) {
				return false;
			}
		}
		for (String label : job.getInclusionLabels()) {
			if (!worker.getLabels().contains(label)) {
				return false;
			}
		}
		return true;
	}
}
