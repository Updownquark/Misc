package org.quark.finance.logic;

import java.awt.EventQueue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.expresso.ExpressoEnv;
import org.observe.expresso.ModelType.ModelInstanceType;
import org.observe.expresso.ModelTypes;
import org.observe.expresso.ObservableExpression;
import org.observe.expresso.ObservableModelSet;
import org.observe.expresso.ObservableModelSet.ModelSetInstance;
import org.observe.util.TypeTokens;
import org.observe.util.swing.ObservableSwingUtils;
import org.qommons.ArrayUtils;
import org.qommons.QommonsUtils;
import org.qommons.StringUtils;
import org.qommons.TimeUtils.ParsedDuration;
import org.qommons.collect.BetterHashSet;
import org.qommons.collect.BetterSet;
import org.qommons.collect.CircularArrayList;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.threading.QommonsTimer;
import org.quark.finance.entities.AssetGroup;
import org.quark.finance.entities.Fund;
import org.quark.finance.entities.Plan;
import org.quark.finance.entities.PlanComponent;
import org.quark.finance.entities.PlanVariable;
import org.quark.finance.entities.PlanVariableType;
import org.quark.finance.entities.Process;
import org.quark.finance.entities.ProcessAction;

public class PlanSimulation {
	static boolean DEBUG = false;

	public static class SimulationResults {
		public final Plan plan;
		public final Fund[] funds;
		public final Process[] processes;
		public final ProcessAction[][] processActions;
		public final AssetGroup[] groups;

		public final ObservableValue<Boolean> finished;
		public final long[][] fundBalances;
		public final long[][] fundGroupBalances;
		public final long[][][] fundProcessContributions;

		public final long[][] processAmounts;
		public final long[][][] processActionAmounts;
		public final long[][] processGroupAmounts;
		private ModelSetInstance models;

		public SimulationResults(Plan plan, int frames, ObservableValue<Boolean> finished) {
			this.plan = plan;
			this.finished = finished;
			funds = plan.getFunds().getValues().toArray();
			processes = plan.getProcesses().getValues().toArray();
			groups = plan.getGroups().getValues().toArray();
			processActions = new ProcessAction[processes.length][];
			fundBalances = new long[plan.getFunds().getValues().size()][frames];
			fundGroupBalances = new long[groups.length][frames];
			fundProcessContributions = new long[funds.length][processes.length][frames];
			processAmounts = new long[plan.getProcesses().getValues().size()][frames];
			processActionAmounts = new long[processAmounts.length][][];
			int p = 0;
			for (Process process : processes) {
				processActions[p] = process.getActions().getValues().toArray();
				processActionAmounts[p] = new long[processActions[p].length][frames];
				p++;
			}
			processGroupAmounts = new long[groups.length][frames];
		}

		public ModelSetInstance getModels() {
			return models;
		}
	}

	public static final SimulationResults simulate(Plan plan, Instant start, Instant end, ParsedDuration resolution, ExpressoEnv env) {
		SettableValue<Boolean> finished = SettableValue.build(boolean.class).withValue(false).build();
		List<Instant> frames = new ArrayList<>();
		TimeZone zone = TimeZone.getDefault();
		for (Instant frame = start; frame.compareTo(end) <= 0; frame = resolution.addTo(frame, zone)) {
			frames.add(frame);
		}
		SimulationResults results = new SimulationResults(plan, frames.size(), finished);
		if (start == null || end == null || plan.getCurrentDate() == null || plan.getCurrentDate().compareTo(end) >= 0) {
			finished.set(true, null);
			return results;
		}
		if (frames.size() <= 1) {
			finished.set(true, null);
			return results;
		}
		QommonsTimer.getCommonInstance().offload(() -> populate(results, start, end, frames, env));
		return results;
	}

	private static void populate(SimulationResults results, Instant start, Instant end, List<Instant> frames, ExpressoEnv env) {
		Plan plan = results.plan;
		// Initialize the models
		ObservableModelSet.Builder modelBuilder = ObservableModelSet.build(ObservableModelSet.JAVA_NAME_CHECKER);
		env = env.with(modelBuilder, null);
		modelBuilder.with("CurrentDate", ModelTypes.Value.forType(Instant.class),
			msi -> SettableValue.build(Instant.class).withDescription("CurrentDate").withValue(start).build());
		modelBuilder.with("PlanStart", ModelTypes.Value.forType(Instant.class),
			msi -> SettableValue.of(Instant.class, start, "PlanStart cannot be modified"));
		/* Here we populate the models with the variables and funds.
		 * This part is pretty complicated here because:
		 * * The variables and funds can all refer to each other in any order
		 * * We don't know the type of each variable, but we want to declare them by their type to avoid the need for casts
		 * 
		 * So we need to do the dependency resolution ourselves.  For each variable/fund:
		 * * Figure out what other variables/funds they depend on and do those first
		 *   * Detect cyclic dependencies and flag all variables/funds on the cycle
		 * * For variables, determine their type by evaluating for any type, but then inject it into the model with its evaluated type
		 */
		Map<String, PlanComponent> components = new HashMap<>();
		PlanVariable[] variables = plan.getVariables().getValues().toArray();
		for (PlanVariable vbl : variables) {
			components.putIfAbsent(vbl.getName(), vbl);
		}
		for (Fund fund : results.funds) {
			components.putIfAbsent(fund.getName(), fund);
		}
		Map<String, String> visited = new HashMap<>();
		Map<String, SettableValue<Money>> fundBalances = new HashMap<>();
		BetterSet<PlanComponent> vblPath = BetterHashSet.build().build();
		for (PlanVariable vbl : variables) {
			install(vbl, modelBuilder, vblPath, visited, components, fundBalances, env);
		}
		for (Fund fund : results.funds) {
			install(fund, modelBuilder, vblPath, visited, components, fundBalances, env);
		}
		env = env.with(modelBuilder.build(), null);

		ModelSetInstance msi = env.getModels().createInstance(null, results.finished.noInitChanges());
		results.models = msi;

		// Initialize fund balances
		Map<String, Integer> fundIndexes = new HashMap<>();
		int fundIdx = 0;
		for (Fund fund : results.funds) {
			fundIndexes.put(fund.getName(), fundIdx++);
			if(fundBalances.containsKey(fund.getName())) {
				try {
					fundBalances.get(fund.getName()).set(fund.getStartingBalance()//
						.evaluate(MONEY_TYPE, env)//
						.get(msi).get(), null);
				} catch(QonfigInterpretationException e) {
					ObservableSwingUtils.onEQ(()->fund.setError(e.getMessage()));
				}
			}
		}

		// Create the process sequence
		TimeZone timeZone = TimeZone.getDefault();
		CircularArrayList<ProcessData> sequence = new CircularArrayList<>();
		for (int p = 0; p < results.processes.length; p++) {
			Process process = results.processes[p];
			String message = null;
			if (process.getPeriod() == null) {
				message = "No period set";
			} else if (results.processActions[p].length == 0) {
				message = "No process actions";
			}
			if (message == null) {
				ProcessData data;
				try {
					data = new ProcessData(process, results.processActions[p], env, msi, timeZone, components, visited);
					data.addInto(sequence);
				} catch (QonfigInterpretationException e) {
					message = e.getMessage();
				}
			}
			if (!Objects.equals(message, process.getError())) {
				String fMsg = message;
				ObservableSwingUtils.onEQ(() -> process.setError(fMsg));
			}
		}
		SettableValue<Instant> currentDate = msi.get("CurrentDate", ModelTypes.Value.forType(Instant.class));
		// Run the processes up to the start time
		while (!sequence.isEmpty() && sequence.getFirst().nextRun.compareTo(start) < 0) {
			ProcessData process = sequence.pop();
			currentDate.set(process.nextRun, null);
			process.run(fundBalances, fundIndexes, timeZone, null, -1);
			process.addInto(sequence);
		}
		int frame;
		Instant time = start;
		if (plan.getCurrentDate().compareTo(start) > 0) {
			frame = Collections.binarySearch(frames, plan.getCurrentDate());
			if (frame < 0) {
				frame = -frame - 1;
			}
			time = frames.get(frame);
		} else {
			frame = 0;
		}

		int initFrame = frame;
		// Run the simulation
		for (; frame < frames.size(); frame++) {
			time = frames.get(frame);
			if (DEBUG) {
				System.out.println("Frame " + frame + ": " + QommonsUtils.print(time.toEpochMilli()));
			}
			currentDate.set(time, null);
			// Run due processes
			while (!sequence.isEmpty() && sequence.getFirst().nextRun.compareTo(time) <= 0) {
				ProcessData process = sequence.pop();
				if (DEBUG) {
					System.out.println("\t" + process);
				}
				process.run(fundBalances, fundIndexes, timeZone, results, frame);
				process.addInto(sequence);
			}

			// Record the updated balances and dump the sinks
			for (int f = 0; f < results.funds.length; f++) {
				Fund fund = results.funds[f];
				if (fundBalances.containsKey(fund.getName())) {
					SettableValue<Money> balance = fundBalances.get(fund.getName());
					results.fundBalances[f][frame] = balance.get().value;
					if (fund.isDumpedAfterFrame()) {
						balance.set(new Money(0), null);
					}
				}
			}
		}

		// Add up the group data
		int[][] fundGroups = new int[results.funds.length][];
		for (int f = 0; f < fundGroups.length; f++) {
			Fund fund = plan.getFunds().getValues().get(f);
			fundGroups[f] = new int[fund.getMemberships().size()];
			for (int g = 0; g < fundGroups[f].length; g++) {
				fundGroups[f][g] = ArrayUtils.indexOf(results.groups, fund.getMemberships().get(g));
			}
		}
		int[][] processGroups = new int[plan.getProcesses().getValues().size()][];
		for (int p = 0; p < processGroups.length; p++) {
			Process process = plan.getProcesses().getValues().get(p);
			processGroups[p] = new int[process.getMemberships().size()];
			for (int g = 0; g < processGroups[p].length; g++) {
				processGroups[p][g] = ArrayUtils.indexOf(results.groups, process.getMemberships().get(g));
			}
		}
		for (int f = 0; f < fundGroups.length; f++) {
			for (int g : fundGroups[f]) {
				for (frame = initFrame; frame < frames.size(); frame++) {
					results.fundGroupBalances[g][frame] += results.fundBalances[f][frame];
				}
			}
		}
		for (int p = 0; p < processGroups.length; p++) {
			for (int g : processGroups[p]) {
				for (frame = initFrame; frame < frames.size(); frame++) {
					results.processGroupAmounts[g][frame] += results.processAmounts[p][frame];
				}
			}
		}

		EventQueue.invokeLater(() -> ((SettableValue<Boolean>) results.finished).set(true, null));
	}

	private static String VARIABLE_OK = "Variable OK";
	private static ModelInstanceType<SettableValue<?>, SettableValue<Money>> MONEY_TYPE = ModelTypes.Value.forType(Money.class);

	private static String install(PlanComponent vbl, ObservableModelSet.Builder modelBuilder, BetterSet<PlanComponent> vblPath,
		Map<String, String> visited, Map<String, PlanComponent> components, Map<String, SettableValue<Money>> fundBalances,
		ExpressoEnv env) {
		ObservableExpression value = vbl instanceof PlanVariable ? ((PlanVariable) vbl).getValue() : ((Fund) vbl).getStartingBalance();
		String message = visited.get(vbl.getName());
		if (VARIABLE_OK.equals(message)) {
			return null; // Already installed
		} else if (message != null) { // Already detected error
		} else if (value == null) {
			if (vbl instanceof PlanVariable) {
				message = vbl.getName() + " value has not been set";
			} else {
				message = vbl.getName() + " starting balance has not been set";
			}
		} else if (!vblPath.add(vbl)) {
			message = "Variable dependency cycle detected: " + StringUtils.print("<-", vblPath, c -> c.getName()) + "<-" + vbl.getName();
		}

		if (message == null) {
			try {
				for (PlanComponent dep : getDependencies(value, components)) {
					message = install(dep, modelBuilder, vblPath, visited, components, fundBalances, env);
					if (message != null) {
						break;
					}
				}
			} finally {
				vblPath.remove(vbl);
			}
			try {
				if (vbl instanceof PlanVariable) {
					ObservableModelSet.ValueContainer<SettableValue<?>, SettableValue<?>> evaluated = value.evaluate(ModelTypes.Value.any(),
						env);
					modelBuilder.with(vbl.getName(), evaluated);
					Class<?> type = TypeTokens.getRawType(evaluated.getType().getType(0));
					PlanVariableType pvt;
					if (type == Money.class) {
						pvt = PlanVariableType.Money;
					} else if (type == Instant.class) {
						pvt = PlanVariableType.Instant;
					} else if (type == ParsedDuration.class) {
						pvt = PlanVariableType.Duration;
					} else if (Number.class.isAssignableFrom(type)) {
						pvt = PlanVariableType.Number;
					} else {
						pvt = PlanVariableType.Other;
					}
					if (((PlanVariable) vbl).getVariableType() != pvt) {
						ObservableSwingUtils.onEQ(() -> ((PlanVariable) vbl).setVariableType(pvt));
					}
				} else {
					SettableValue<Money> balance = SettableValue.build(Money.class)//
						.withDescription(vbl.getName() + "_balance")//
						.withValue(new Money(0)).build();
					fundBalances.put(vbl.getName(), balance);
					modelBuilder.with(vbl.getName(), ObservableModelSet.container(msi -> balance, MONEY_TYPE));
				}
			} catch (QonfigInterpretationException e) {
				message = vbl.getName() + ": " + e.getMessage();
			}
		}

		if (!Objects.equals(vbl.getError(), message)) {
			String fMsg = message;
			ObservableSwingUtils.onEQ(() -> vbl.setError(fMsg));
		}
		if (message != null) {
			visited.put(vbl.getName(), message);
			return message;
		} else {
			visited.put(vbl.getName(), VARIABLE_OK);
			return null;
		}
	}

	private static List<PlanComponent> getDependencies(ObservableExpression value, Map<String, PlanComponent> components) {
		List<PlanComponent> vcs = null;
		if (value instanceof NamedEntityExpression) {
			PlanComponent comp = components.get(value.toString());
			if (comp != null) {
				vcs = Collections.singletonList(comp);
			}
		} else {
			for (ObservableExpression child : value.getChildren()) {
				List<PlanComponent> childPCs = getDependencies(child, components);
				if (!childPCs.isEmpty()) {
					if (vcs == null) {
						vcs = new ArrayList<>();
					}
					vcs.addAll(childPCs);
				}
			}
		}
		if (vcs == null) {
			vcs = Collections.emptyList();
		}
		return vcs;
	}

	static class ProcessData implements Comparable<ProcessData> {
		final Process process;
		final int processIndex;
		final ProcessActionData[] actions;
		Instant nextRun;
		final ObservableValue<Boolean> active;
		final Instant start;
		final Instant end;

		ProcessData(Process process, ProcessAction[] actions, ExpressoEnv env, ModelSetInstance msi, TimeZone timeZone,
			Map<String, PlanComponent> components,
			Map<String, String> visited) throws QonfigInterpretationException {
			this.process = process;
			processIndex = process.getPlan().getProcesses().getValues().indexOf(process);
			this.actions = new ProcessActionData[actions.length];
			components = new HashMap<>(components);
			visited = new HashMap<>(visited);
			if (!process.getLocalVariables().getValues().isEmpty()) {
				ObservableModelSet.WrappedBuilder modelBuilder = msi.getModel().wrap();
				env = env.with(modelBuilder, null);
				BetterSet<PlanComponent> vblPath = BetterHashSet.build().build();
				for (PlanVariable vbl : process.getLocalVariables().getValues()) {
					install(vbl, modelBuilder, vblPath, visited, components, null, env);
				}
				ObservableModelSet.Wrapped wrapped = modelBuilder.build();
				env = env.with(wrapped, null);
				msi = wrapped.wrap(msi).build();
			}
			for (int a = 0; a < actions.length; a++) {
				if (actions[a].getFund() != null && actions[a].getAmount() != null) {
					this.actions[a] = new ProcessActionData(a, actions[a].getFund(), //
						actions[a].getAmount().evaluate(ModelTypes.Value.forType(Money.class), env).apply(msi));
				}
			}
			start=process.getStart()==null ? null : process.getStart().evaluate(ModelTypes.Value.forType(Instant.class), env).get(msi).get();
			end=process.getEnd()==null ? null : process.getEnd().evaluate(ModelTypes.Value.forType(Instant.class), env).get(msi).get();
			active = process.getActive() == null ? ObservableValue.of(true)
				: process.getActive().evaluate(ModelTypes.Value.forType(boolean.class), env).get(msi);
			if (start != null) {
				nextRun = start;
			} else {
				nextRun = process.getPlan().getCurrentDate();
			}
			if (nextRun != null && end != null && nextRun.compareTo(end) > 0) {
				nextRun = null;
			}
		}

		@Override
		public int compareTo(ProcessData o) {
			return nextRun.compareTo(o.nextRun);
		}

		void addInto(CircularArrayList<ProcessData> sequence) {
			if (nextRun == null) {
				return;
			}
			int index = Collections.binarySearch(sequence, this);
			if (index >= 0) {
				while (index < sequence.size() - 1 && sequence.get(index).compareTo(this) == 0) {
					index++;
				}
			} else {
				index=-index-1;
			}
			sequence.add(index, this);
		}

		void run(Map<String, SettableValue<Money>> fundBalances, Map<String, Integer> fundIndexes, TimeZone timeZone,
			SimulationResults results, int frame) {
			if (active.get()) {
				for (ProcessActionData action : actions) {
					if (action != null) {
						action.run(fundBalances, fundIndexes, results, processIndex, frame);
					}
				}
			}

			nextRun = process.getPeriod().addTo(nextRun, timeZone);
			if (nextRun != null && end != null && nextRun.compareTo(end) > 0) {
				nextRun = null;
			}
		}

		@Override
		public String toString() {
			return process.getName() + " at " + nextRun;
		}
	}

	static class ProcessActionData {
		final int actionIndex;
		final Fund fund;
		final SettableValue<Money> amount;

		public ProcessActionData(int actionIndex, Fund fund, SettableValue<Money> amount) {
			this.actionIndex = actionIndex;
			this.fund = fund;
			this.amount = amount;
		}

		void run(Map<String, SettableValue<Money>> fundBalances, Map<String, Integer> fundIndexes, SimulationResults results,
			int processIndex, int frame) {
			Money money = amount.get();
			if (money.value == 0) {
				return;
			}
			if (frame >= 0 && results != null) {
				results.processAmounts[processIndex][frame] += money.value;
				results.processActionAmounts[processIndex][actionIndex][frame] += money.value;
				results.fundProcessContributions[fundIndexes.get(fund.getName())][processIndex][frame] += money.value;
			}

			SettableValue<Money> balance = fundBalances.get(fund.getName());
			balance.set(new Money(balance.get().value + money.value), null);
			if (DEBUG) {
				System.out.println("\t\t" + fund.getName() + (money.value < 0 ? " " : " +") + money + " to " + balance.get());
			}
		}

		@Override
		public String toString() {
			return fund + ": " + amount;
		}
	}
}
