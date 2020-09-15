package org.quark.chores.ui;

import java.util.List;

import javax.swing.JPanel;

import org.observe.SettableValue;
import org.observe.config.ObservableConfig;
import org.observe.config.ObservableConfigFormat;
import org.observe.config.ObservableConfigFormatSet;
import org.observe.config.SyncValueSet;
import org.observe.util.TypeTokens;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.PanelPopulation;
import org.qommons.io.Format;
import org.quark.chores.entities.AssignedJob;
import org.quark.chores.entities.Assignment;
import org.quark.chores.entities.Job;
import org.quark.chores.entities.Worker;

public class ChoresUI extends JPanel {
	private final SyncValueSet<Job> theJobs;
	private final SyncValueSet<Worker> theWorkers;
	private final SyncValueSet<Assignment> theAssignments;
	private final ObservableConfig theConfig;

	private final SettableValue<Worker> theSelectedWorker;
	private final SettableValue<Job> theSelectedJob;
	private final SettableValue<Assignment> theSelectedAssignment;

	private final StatusPanel theStatusPanel;
	private final WorkersPanel theWorkersPanel;
	private final JobsPanel theJobsPanel;
	private final JobAdminPanel theAdminPanel;

	public ChoresUI(SyncValueSet<Job> jobs, SyncValueSet<Worker> workers, SyncValueSet<Assignment> assignments, ObservableConfig config) {
		theJobs = jobs;
		theWorkers = workers;
		theAssignments = assignments;
		theConfig = config;

		theSelectedWorker = SettableValue.build(Worker.class).safe(false).build();
		theSelectedJob = SettableValue.build(Job.class).safe(false).build();
		theSelectedAssignment = SettableValue.build(Assignment.class).safe(false).build();

		// Select the last assignment initially
		theSelectedAssignment.set(getLastAssignment(theAssignments.getValues()), null);
		theAssignments.getValues().changes().act(evt -> {
			switch (evt.type) {
			case add:
				// When a new assignment is created (after the current one), select it
				Assignment last = getLastAssignment(evt.getValues());
				if (last != null
						&& (theSelectedAssignment.get() == null || last.getDate().compareTo(theSelectedAssignment.get().getDate()) > 0)) {
					theSelectedAssignment.set(last, evt);
				}
				break;
			case remove:
				// If the selected assignment is deleted, select the last assignment
				if (evt.getValues().contains(theSelectedAssignment.get())) {
					theSelectedAssignment.set(getLastAssignment(theAssignments.getValues()), evt);
				}
				break;
			case set:
				// If the selected assignment is changed, fire an update
				if (evt.getValues().contains(theSelectedAssignment.get())) {
					theSelectedAssignment.set(theSelectedAssignment.get(), evt);
				}
				break;
			}
		});

		theStatusPanel = new StatusPanel(this);
		theWorkersPanel = new WorkersPanel(this);
		theJobsPanel = new JobsPanel(this);
		theAdminPanel = new JobAdminPanel(this);

		initComponents();
	}

	private static Assignment getLastAssignment(List<Assignment> assignments) {
		Assignment lastAssignment = null;
		for (Assignment assign : assignments) {
			if (lastAssignment == null || assign.getDate().compareTo(lastAssignment.getDate()) > 0) {
				lastAssignment = assign;
			}
		}
		return lastAssignment;
	}

	public ObservableConfig getConfig() {
		return theConfig;
	}

	public SyncValueSet<Job> getJobs() {
		return theJobs;
	}

	public SyncValueSet<Worker> getWorkers() {
		return theWorkers;
	}

	public SyncValueSet<Assignment> getAssignments() {
		return theAssignments;
	}

	public SettableValue<Assignment> getSelectedAssignment() {
		return theSelectedAssignment;
	}

	public SettableValue<Worker> getSelectedWorker() {
		return theSelectedWorker;
	}

	public SettableValue<Job> getSelectedJob() {
		return theSelectedJob;
	}

	private void initComponents() {
		SettableValue<String> selectedTab = theConfig.asValue(String.class).at("selected-tab").withFormat(Format.TEXT, () -> "settings")
				.buildValue(null);

		PanelPopulation.populateVPanel(this, null)//
				.addTabs(tabs -> {
					tabs.fill().fillV().withSelectedTab(selectedTab);
					tabs.withVTab("status", theStatusPanel::addPanel, tab -> tab.setName("Status"));
					tabs.withVTab("workers", theWorkersPanel::addPanel, tab -> tab.setName("Workers"));
					tabs.withVTab("jobs", theJobsPanel::addPanel, tab -> tab.setName("Jobs"));
					tabs.withVTab("admin", theAdminPanel::addPanel, tab -> tab.setName("Admin"));
				});
	}

	public static void main(String[] args) {
		ObservableSwingUtils.buildUI()//
				.withConfig("chores-config").withConfigAt("Chores.xml")//
				.withTitle("Chore Helper").systemLandF().build(config -> {
					ObservableConfigFormatSet formats = new ObservableConfigFormatSet();
					SyncValueSet<Job> jobs = getJobs(config, formats, "jobs/job");
					SyncValueSet<Worker> workers = getWorkers(config, formats, "workers/worker", jobs);
					SyncValueSet<Assignment> assignments = getAssignments(config, formats, "assignments/assignment", jobs, workers);
					return new ChoresUI(jobs, workers, assignments, config);
				});
	}

	private static SyncValueSet<Job> getJobs(ObservableConfig config, ObservableConfigFormatSet formats, String path) {
		return config.asValue(Job.class).withFormatSet(formats).at(path).buildEntitySet(null);
	}

	private static SyncValueSet<Worker> getWorkers(ObservableConfig config, ObservableConfigFormatSet formats, String path,
			SyncValueSet<Job> jobs) {
		ObservableConfigFormat<Job> jobRefFormat = ObservableConfigFormat.<Job> buildReferenceFormat(fv -> jobs.getValues(), null)//
				.withField("id", Job::getId, ObservableConfigFormat.LONG).build();
		return config.asValue(Worker.class).withFormatSet(formats).asEntity(workerConfig -> {
			workerConfig.withFieldFormat(Worker::getJobPreferences, ObservableConfigFormat.ofMap(jobs.getValues().getType(),
					TypeTokens.get().INT, "job", "preference", jobRefFormat, ObservableConfigFormat.INT));
		}).at(path).buildEntitySet(null);
	}

	private static SyncValueSet<Assignment> getAssignments(ObservableConfig config, ObservableConfigFormatSet formats, String path,
			SyncValueSet<Job> jobs, SyncValueSet<Worker> workers) {
		ObservableConfigFormat<Job> jobRefFormat = ObservableConfigFormat.<Job> buildReferenceFormat(fv -> jobs.getValues(), null)//
				.withField("id", Job::getId, ObservableConfigFormat.LONG).build();
		ObservableConfigFormat<Worker> workerRefFormat = ObservableConfigFormat
				.<Worker> buildReferenceFormat(fv -> workers.getValues(), null)//
				.withField("id", Worker::getId, ObservableConfigFormat.LONG).build();
		ObservableConfigFormat.EntityConfigFormat<AssignedJob> assignedJobFormat = ObservableConfigFormat
				.buildEntities(TypeTokens.get().of(AssignedJob.class), formats)//
				.withFieldFormat(AssignedJob::getJob, jobRefFormat)//
				.withFieldFormat(AssignedJob::getWorker, workerRefFormat).build();
		return config.asValue(Assignment.class).withFormatSet(formats).asEntity(assignmentConfig -> {
			assignmentConfig.withFieldFormat(Assignment::getAssignments,
					ObservableConfigFormat.ofEntitySet(assignedJobFormat, "assignment"));
		}).at(path).buildEntitySet(null);
	}
}
