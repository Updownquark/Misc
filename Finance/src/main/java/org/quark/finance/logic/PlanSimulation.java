package org.quark.finance.logic;

import java.awt.EventQueue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

import org.observe.Observable;
import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.expresso.CompiledExpressoEnv;
import org.observe.expresso.ExpressoInterpretationException;
import org.observe.expresso.InterpretedExpressoEnv;
import org.observe.expresso.ModelInstantiationException;
import org.observe.expresso.ModelType.ModelInstanceType;
import org.observe.expresso.ModelTypes;
import org.observe.expresso.ObservableExpression;
import org.observe.expresso.ObservableModelSet;
import org.observe.expresso.ObservableModelSet.CompiledModelValue;
import org.observe.expresso.ObservableModelSet.InterpretedModelSet;
import org.observe.expresso.ObservableModelSet.InterpretedValueSynth;
import org.observe.expresso.ObservableModelSet.ModelComponentId;
import org.observe.expresso.ObservableModelSet.ModelInstantiator;
import org.observe.expresso.ObservableModelSet.ModelSetInstance;
import org.observe.expresso.ObservableModelSet.ModelValueInstantiator;
import org.observe.expresso.TypeConversionException;
import org.observe.util.TypeTokens;
import org.qommons.ArrayUtils;
import org.qommons.QommonsUtils;
import org.qommons.TimeUtils.ParsedDuration;
import org.qommons.collect.CircularArrayList;
import org.qommons.ex.ExceptionHandler;
import org.qommons.io.FilePosition;
import org.qommons.io.LocatedFilePosition;
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
	private static ModelInstanceType<SettableValue<?>, SettableValue<Money>> MONEY_TYPE = ModelTypes.Value.forType(Money.class);

	private final Plan thePlan;
	private final Map<String, ModelComponentId> theVariables;
	private final Map<String, PlanComponent> theComponents;
	private final List<ProcessData> theProcesses;
	private final CompiledExpressoEnv theEnv;

	public PlanSimulation(Plan plan, CompiledExpressoEnv env) {
		thePlan = plan;
		theVariables = new HashMap<>();
		theComponents = new HashMap<>();

		for (PlanVariable vbl : plan.getVariables().getValues()) {
			error(vbl, null, false);
			theComponents.putIfAbsent(vbl.getName(), vbl);
		}
		for (Fund fund : plan.getFunds().getValues()) {
			error(fund, null, false);
			theComponents.putIfAbsent(fund.getName(), fund);
		}

		ObservableModelSet.Builder modelBuilder = ObservableModelSet.build("simulation", ObservableModelSet.JAVA_NAME_CHECKER);
		env = env.with(modelBuilder);
		modelBuilder.with("CurrentDate",
			InterpretedValueSynth.literal(ModelTypes.Value.forType(Instant.class),
				SettableValue.build(Instant.class).withDescription("CurrentDate").withValue(plan.getCurrentDate()).build(), "CurrentDate"),
			null);
		theVariables.put("CurrentDate", modelBuilder.getLocalComponent("CurrentDate").getIdentity());
		modelBuilder.with("PlanStart", InterpretedValueSynth.literal(ModelTypes.Value.forType(Instant.class),
			SettableValue.of(Instant.class, plan.getCurrentDate(), "PlanStart cannot be modified"), "PlanStart"), null);
		theVariables.put("PlanStart", modelBuilder.getLocalComponent("PlanStart").getIdentity());
		env = env.with(modelBuilder);

		for (PlanVariable vbl : plan.getVariables().getValues()) {
			install(vbl, modelBuilder, env, theVariables);
		}
		for (Fund fund : plan.getFunds().getValues()) {
			install(fund, modelBuilder, env, theVariables);
		}
		theEnv = env.with(modelBuilder.build());

		List<ProcessData> processes = new ArrayList<>(plan.getProcesses().getValues().size());
		for (Process process : plan.getProcesses().getValues()) {
			error(process, null, false);
			if (process.getPeriod() == null) {
				process.setError("No period set");
			}
			processes.add(new ProcessData(this, process, theEnv));
		}
		theProcesses = Collections.unmodifiableList(processes);
	}

	public Plan getPlan() {
		return thePlan;
	}

	public List<ProcessData> getProcesses() {
		return theProcesses;
	}

	public CompiledExpressoEnv getEnv() {
		return theEnv;
	}

	public Interpreted interpret(InterpretedExpressoEnv env)
		throws ExpressoInterpretationException, TypeConversionException {
		return new Interpreted(this, env);
	}

	public static class Interpreted {
		private final PlanSimulation theDefinition;
		private final List<ProcessData.Interpreted> theProcesses;
		private final InterpretedExpressoEnv theEnv;

		Interpreted(PlanSimulation def, InterpretedExpressoEnv env)
			throws ExpressoInterpretationException, TypeConversionException {
			theDefinition = def;
			env = env.forChild(def.getEnv());
			env.getModels().interpret(env);
			theEnv = env;

			List<ProcessData.Interpreted> processes = new ArrayList<>(theDefinition.getProcesses().size());
			for (ProcessData process : theDefinition.getProcesses()) {
				processes.add(process.interpret(env));
			}
			theProcesses = Collections.unmodifiableList(processes);
		}

		public PlanSimulation getDefinition() {
			return theDefinition;
		}

		public List<ProcessData.Interpreted> getProcesses() {
			return theProcesses;
		}

		public InterpretedExpressoEnv getEnv() {
			return theEnv;
		}

		public Instantiator instantiate() {
			return new Instantiator(this);
		}
	}

	public static class Instantiator {
		private final Plan thePlan;
		private final Map<String, ModelComponentId> theVariables;
		private final List<ProcessData.Instantiator> theProcesses;
		private final ModelInstantiator theModels;

		Instantiator(Interpreted interpreted) {
			thePlan = interpreted.getDefinition().getPlan();
			theVariables = interpreted.getDefinition().theVariables;
			theModels = interpreted.getEnv().getModels().instantiate();
			List<ProcessData.Instantiator> processes = new ArrayList<>(interpreted.getProcesses().size());
			for (ProcessData.Interpreted process : interpreted.getProcesses()) {
				processes.add(process.instantiate());
			}
			theProcesses = Collections.unmodifiableList(processes);
		}

		public Plan getPlan() {
			return thePlan;
		}

		public List<ProcessData.Instantiator> getProcesses() {
			return theProcesses;
		}

		public ModelInstantiator getModels() {
			return theModels;
		}

		public void instantiate() {
			theModels.instantiate();
			for (ProcessData.Instantiator process : theProcesses) {
				process.instantiate();
			}
		}

		public Instance create(Observable<?> until) throws ModelInstantiationException {
			ModelSetInstance models = getModels().createInstance(until).build();
			return new Instance(this, models);
		}
	}

	public static class Instance {
		private final Plan thePlan;
		private final Map<String, ModelComponentId> theVariables;
		private final List<ProcessData.Instance> theProcesses;
		private final ModelSetInstance theModels;

		Instance(Instantiator instantiator, ModelSetInstance models) throws ModelInstantiationException {
			thePlan = instantiator.getPlan();
			theVariables = instantiator.theVariables;
			theModels = models;
			List<ProcessData.Instance> processes = new ArrayList<>(instantiator.getProcesses().size());
			for (ProcessData.Instantiator process : instantiator.getProcesses()) {
				processes.add(process.create(models));
			}
			theProcesses = Collections.unmodifiableList(processes);
		}

		public Plan getPlan() {
			return thePlan;
		}

		public List<ProcessData.Instance> getProcesses() {
			return theProcesses;
		}

		public ModelComponentId getVariable(String name) {
			return theVariables.get(name);
		}

		public ModelSetInstance getModels() {
			return theModels;
		}

		public SimulationResults run(Instant start, Instant end, ParsedDuration resolution) {
			SettableValue<Boolean> finished = SettableValue.build(boolean.class).withValue(false).build();
			List<Instant> frames = new ArrayList<>();
			TimeZone zone = TimeZone.getDefault();
			for (Instant frame = start; frame.compareTo(end) <= 0; frame = resolution.addTo(frame, zone)) {
				frames.add(frame);
			}
			SimulationResults results = new SimulationResults(this, //
				frames.toArray(new Instant[frames.size()]), finished, new LinkedHashMap<>());
			if (start == null || end == null || thePlan.getCurrentDate() == null || thePlan.getCurrentDate().compareTo(end) >= 0) {
				finished.set(true, null);
				return results;
			}
			if (frames.size() <= 1) {
				finished.set(true, null);
				return results;
			}

			QommonsTimer.getCommonInstance().offload(() -> execute(results, start, end));
			return results;
		}

		private void execute(SimulationResults results, Instant start, Instant end) {
			// Retrieve fund balances
			Map<String, SettableValue<Money>> fundBalances = new HashMap<>();
			Map<String, Integer> fundIndexes = new HashMap<>();
			int fundIdx = 0;
			for (Fund fund : results.funds) {
				fundIndexes.put(fund.getName(), fundIdx++);
				ModelComponentId vbl = theVariables.get(fund.getName());
				try {
					fundBalances.put(fund.getName(), (SettableValue<Money>) theModels.get(vbl));
				} catch (ModelInstantiationException e) {
					e.printStackTrace();
					return;
				}
			}

			// Create the process sequence
			TimeZone timeZone = TimeZone.getDefault();
			CircularArrayList<ProcessData.Instance> sequence = new CircularArrayList<>();
			sequence.addAll(theProcesses);
			Collections.sort(sequence);
			SettableValue<Instant> currentDate;
			try {
				currentDate = (SettableValue<Instant>) theModels.get(theVariables.get("CurrentDate"));
			} catch (ModelInstantiationException e) {
				e.printStackTrace();
				return;
			}

			// Run the processes up to the start time
			while (!sequence.isEmpty() && sequence.getFirst().getNextRun().compareTo(start) < 0) {
				ProcessData.Instance process = sequence.pop();
				currentDate.set(process.getNextRun(), null);
				process.run(fundBalances, fundIndexes, timeZone, null, -1);
				process.addInto(sequence);
			}
			int frame;
			Instant time = start;
			if (thePlan.getCurrentDate().compareTo(start) > 0) {
				frame = Arrays.binarySearch(results.frames, thePlan.getCurrentDate());
				if (frame < 0) {
					frame = -frame - 1;
				}
				time = results.frames[frame];
			} else {
				frame = 0;
			}
			for (int f = 0; f < results.funds.length; f++) {
				if (results.funds[f].isDumpedAfterFrame()) {
					fundBalances.get(results.funds[f].getName()).set(new Money(0), null);
				}
			}

			int initFrame = frame;
			// Run the simulation
			for (; frame < results.frames.length; frame++) {
				time = results.frames[frame];
				if (DEBUG) {
					System.out.println("Frame " + frame + ": " + QommonsUtils.print(time.toEpochMilli()));
				}
				currentDate.set(time, null);
				// Run due processes
				while (!sequence.isEmpty() && sequence.getFirst().getNextRun().compareTo(time) <= 0) {
					ProcessData.Instance process = sequence.pop();
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
						Money amount = balance.get();
						results.fundBalances[f][frame] = amount == null ? 0 : amount.value;
						if (fund.isDumpedAfterFrame()) {
							balance.set(new Money(0), null);
						}
					}
				}
			}

			// Add up the group data
			int[][] fundGroups = new int[results.funds.length][];
			for (int f = 0; f < fundGroups.length; f++) {
				Fund fund = results.funds[f];
				fundGroups[f] = new int[fund.getMemberships().size()];
				for (int g = 0; g < fundGroups[f].length; g++) {
					fundGroups[f][g] = ArrayUtils.indexOf(results.groups, fund.getMemberships().get(g));
				}
			}
			int[][] processGroups = new int[results.processes.length][];
			for (int p = 0; p < processGroups.length; p++) {
				Process process = results.processes[p];
				processGroups[p] = new int[process.getMemberships().size()];
				for (int g = 0; g < processGroups[p].length; g++) {
					processGroups[p][g] = ArrayUtils.indexOf(results.groups, process.getMemberships().get(g));
				}
			}
			for (int f = 0; f < fundGroups.length; f++) {
				for (int g : fundGroups[f]) {
					for (frame = initFrame; frame < results.frames.length; frame++) {
						results.fundGroupBalances[g][frame] += results.fundBalances[f][frame];
					}
				}
			}
			for (int p = 0; p < processGroups.length; p++) {
				for (int g : processGroups[p]) {
					for (frame = initFrame; frame < results.frames.length; frame++) {
						results.processGroupAmounts[g][frame] += results.processAmounts[p][frame];
					}
				}
			}

			EventQueue.invokeLater(() -> ((SettableValue<Boolean>) results.finished).set(true, null));
		}
	}

	static void install(PlanComponent vbl, ObservableModelSet.Builder modelBuilder, CompiledExpressoEnv env,
		Map<String, ModelComponentId> variables) {
		if (vbl.getName() == null || vbl.getName().isEmpty()) {
			return;
		}
		ObservableExpression value = vbl instanceof PlanVariable ? ((PlanVariable) vbl).getValue() : ((Fund) vbl).getStartingBalance();
		if (vbl instanceof PlanVariable) {
			modelBuilder.withMaker(vbl.getName(), CompiledModelValue.of(vbl.getName(), ModelTypes.Value, iEnv -> {
				if (value == null) {
					error(vbl, "No value set", false);
					return InterpretedValueSynth.literalValue(TypeTokens.get().INT, 0, "Missing value");
				}
				try {
					InterpretedValueSynth<SettableValue<?>, ?> interpreted = value.evaluate(ModelTypes.Value.any(), iEnv, 0,
						ExceptionHandler.thrower2());
					Class<?> type = TypeTokens.get().wrap(TypeTokens.getRawType(interpreted.getType().getType(0)));
					if (Number.class.isAssignableFrom(type)) {
						EventQueue.invokeLater(() -> ((PlanVariable) vbl).setVariableType(PlanVariableType.Number));
					} else if (ParsedDuration.class.isAssignableFrom(type)) {
						EventQueue.invokeLater(() -> ((PlanVariable) vbl).setVariableType(PlanVariableType.Duration));
					} else if (Instant.class.isAssignableFrom(type)) {
						EventQueue.invokeLater(() -> ((PlanVariable) vbl).setVariableType(PlanVariableType.Instant));
					} else if (Money.class.isAssignableFrom(type)) {
						EventQueue.invokeLater(() -> ((PlanVariable) vbl).setVariableType(PlanVariableType.Money));
					} else {
						EventQueue.invokeLater(() -> ((PlanVariable) vbl).setVariableType(PlanVariableType.Other));
					}
					return interpreted;
				} catch (ExpressoInterpretationException e) {
					error(vbl, e.getMessage(), false);
					int pos = e.getErrorOffset();
					throw new ExpressoInterpretationException(e.getMessage(),
						new LocatedFilePosition(vbl.getName(), new FilePosition(pos, 0, pos)), e.getErrorLength(), e);
				} catch (TypeConversionException e) {
					vbl.setError(e.getMessage());
					throw new ExpressoInterpretationException(e.getMessage(), new LocatedFilePosition(vbl.getName(), FilePosition.START),
						value.getExpressionLength(), e);
				}
			}), null);
		} else { // Fund balance. Initialized, not slaved, to the value
			modelBuilder.withMaker(vbl.getName(), CompiledModelValue.of(vbl.getName(), ModelTypes.Value, iEnv -> {
				InterpretedValueSynth<SettableValue<?>, SettableValue<Money>> interpretedInitBalance;
				try {
					interpretedInitBalance = value == null ? null : value.evaluate(MONEY_TYPE, iEnv, 0, ExceptionHandler.thrower2());
				} catch (ExpressoInterpretationException e) {
					error(vbl, e.getMessage(), false);
					int pos = e.getErrorOffset();
					throw new ExpressoInterpretationException(e.getMessage(),
						new LocatedFilePosition(vbl.getName(), new FilePosition(pos, 0, pos)), e.getErrorLength(), e);
				} catch (TypeConversionException e) {
					error(vbl, e.getMessage(), false);
					throw new ExpressoInterpretationException(e.getMessage(), new LocatedFilePosition(vbl.getName(), FilePosition.START),
						value.getExpressionLength(), e);
				}
				return InterpretedValueSynth.of(MONEY_TYPE, () -> {
					ModelValueInstantiator<SettableValue<Money>> initialBalanceInstantiator;
					initialBalanceInstantiator = interpretedInitBalance == null ? null : interpretedInitBalance.instantiate();
					return ModelValueInstantiator.of(msi -> {
						SettableValue.Builder<Money> initBalanceBuilder = SettableValue.build(Money.class)//
							.withDescription(vbl.getName() + "_balance");
						if (initialBalanceInstantiator != null) {
							initialBalanceInstantiator.instantiate();
							SettableValue<Money> initBalance = initialBalanceInstantiator.get(msi);
							initBalanceBuilder.withValue(initBalance.get());
						}
						return initBalanceBuilder.build();
					});
				});
			}), null);
		}
		variables.put(vbl.getName(), modelBuilder.getLocalComponent(vbl.getName()).getIdentity());
	}

	static void error(PlanComponent component, String error, boolean ifNoError) {
		String currentError = component.getError();
		if (Objects.equals(currentError, error)) {
			return;
		}
		if (ifNoError && currentError != null) {
			return;
		}
		EventQueue.invokeLater(() -> component.setError(error));
	}

	public static class SimulationResults {
		public final PlanSimulation.Instance simulation;
		public final Fund[] funds;
		public final Process[] processes;
		public final ProcessAction[][] processActions;
		public final AssetGroup[] groups;

		public final ObservableValue<Boolean> finished;

		public final Instant[] frames;
		public final long[][] fundBalances;
		public final long[][] fundGroupBalances;
		public final long[][][] fundProcessContributions;

		public final long[][] processAmounts;
		public final long[][][] processActionAmounts;
		public final long[][] processGroupAmounts;

		public SimulationResults(PlanSimulation.Instance sim, Instant[] frames, ObservableValue<Boolean> finished,
			Map<String, ModelComponentId> variables) {
			simulation = sim;
			this.finished = finished;
			funds = sim.getPlan().getFunds().getValues().toArray();
			processes = sim.getPlan().getProcesses().getValues().toArray();
			groups = sim.getPlan().getGroups().getValues().toArray();
			processActions = new ProcessAction[processes.length][];
			this.frames = frames;
			fundBalances = new long[sim.getPlan().getFunds().getValues().size()][frames.length];
			fundGroupBalances = new long[groups.length][frames.length];
			fundProcessContributions = new long[funds.length][processes.length][frames.length];
			processAmounts = new long[sim.getPlan().getProcesses().getValues().size()][frames.length];
			processActionAmounts = new long[processAmounts.length][][];
			int p = 0;
			for (Process process : processes) {
				processActions[p] = process.getActions().getValues().toArray();
				processActionAmounts[p] = new long[processActions[p].length][frames.length];
				p++;
			}
			processGroupAmounts = new long[groups.length][frames.length];
		}

		public ModelSetInstance getModels() {
			return simulation.getModels();
		}
	}

	public static class ProcessData {
		final Process theProcess;
		final int theProcessIndex;
		private final CompiledExpressoEnv theEnv;
		final Map<String, ModelComponentId> theVariables;

		ProcessData(PlanSimulation simulation, Process process, CompiledExpressoEnv env) {
			this.theProcess = process;
			theProcessIndex = process.getPlan().getProcesses().getValues().indexOf(process);
			theVariables = new LinkedHashMap<>();
			if (!process.getLocalVariables().getValues().isEmpty()) {
				ObservableModelSet.Builder modelBuilder = ObservableModelSet
					.build("process[" + process.getName() + "].local", ObservableModelSet.JAVA_NAME_CHECKER)//
					.withAll(env.getModels());
				env = env.with(modelBuilder);
				for (PlanVariable vbl : process.getLocalVariables().getValues()) {
					error(vbl, null, false);
					install(vbl, modelBuilder, env, theVariables);
				}
				env = env.with(modelBuilder.build());
			}
			for (ProcessAction action : theProcess.getActions().getValues()) {
				error(action, null, false);
			}
			theEnv = env;
		}

		public Process getProcess() {
			return theProcess;
		}

		public int getProcessIndex() {
			return theProcessIndex;
		}

		public ModelComponentId getVariable(String name) {
			return theVariables.get(name);
		}

		public Interpreted interpret(InterpretedExpressoEnv env) {
			env = env.forChild(theEnv);
			return new Interpreted(this, env);
		}

		@Override
		public String toString() {
			return theProcess.getName();
		}

		public static class Interpreted {
			private final ProcessData theDefinition;
			private final InterpretedModelSet theModels;
			private final InterpretedValueSynth<SettableValue<?>, SettableValue<Instant>> theStart;
			private final InterpretedValueSynth<SettableValue<?>, SettableValue<Instant>> theEnd;
			private final InterpretedValueSynth<SettableValue<?>, SettableValue<Boolean>> isActive;
			private final List<ProcessActionData> theActions;

			Interpreted(ProcessData definition, InterpretedExpressoEnv env) {
				theDefinition = definition;

				InterpretedValueSynth<SettableValue<?>, SettableValue<Instant>> start = null;
				InterpretedValueSynth<SettableValue<?>, SettableValue<Instant>> end = null;
				InterpretedValueSynth<SettableValue<?>, SettableValue<Boolean>> active = null;

				InterpretedModelSet models = env.getModels();
				try {
					models.interpret(env);
				} catch (ExpressoInterpretationException e) {
					if (theDefinition.getProcess().getError() == null) {
						error(theDefinition.getProcess(), e.getMessage(), true);
					}
					e.printStackTrace();
					models = null;
				}
				theModels = models;

				if (definition.getProcess().getStart() != null) {
					try {
						start = definition.getProcess().getStart().evaluate(ModelTypes.Value.forType(Instant.class), env, 0,
							ExceptionHandler.thrower2());
					} catch (ExpressoInterpretationException | TypeConversionException e) {
						error(theDefinition.getProcess(), e.getMessage(), true);
						e.printStackTrace();
					}
				}
				theStart = start;

				if (definition.getProcess().getEnd() != null) {
					try {
						end = definition.getProcess().getEnd().evaluate(ModelTypes.Value.forType(Instant.class), env, 0,
							ExceptionHandler.thrower2());
					} catch (ExpressoInterpretationException | TypeConversionException e) {
						error(theDefinition.getProcess(), e.getMessage(), true);
						e.printStackTrace();
					}
				}
				theEnd = end;

				if (definition.getProcess().getActive() != null) {
					try {
						active = definition.getProcess().getActive().evaluate(ModelTypes.Value.forType(boolean.class), env, 0,
							ExceptionHandler.thrower2());
					} catch (ExpressoInterpretationException | TypeConversionException e) {
						error(theDefinition.getProcess(), e.getMessage(), true);
						e.printStackTrace();
					}
				} else {
					active = InterpretedValueSynth.literalValue(TypeTokens.get().BOOLEAN, true, "true");
				}
				isActive = active;

				List<ProcessActionData> actions = new ArrayList<>(definition.getProcess().getActions().getValues().size());
				for (ProcessAction action : definition.getProcess().getActions().getValues()) {
					actions.add(new ProcessActionData(action, env));
					if (action.getError() != null) {
						error(theDefinition.getProcess(), action.getError(), true);
					}
				}
				theActions = Collections.unmodifiableList(actions);
			}

			public ProcessData getDefinition() {
				return theDefinition;
			}

			public InterpretedModelSet getModels() {
				return theModels;
			}

			public List<ProcessActionData> getActions() {
				return theActions;
			}

			public InterpretedValueSynth<SettableValue<?>, SettableValue<Instant>> getStart() {
				return theStart;
			}

			public InterpretedValueSynth<SettableValue<?>, SettableValue<Instant>> getEnd() {
				return theEnd;
			}

			public InterpretedValueSynth<SettableValue<?>, SettableValue<Boolean>> isActive() {
				return isActive;
			}

			public Instantiator instantiate() {
				return new Instantiator(this);
			}
		}

		public static class Instantiator {
			private final Process theProcess;
			private final int theProcessIndex;
			private final ModelInstantiator theModels;
			private final Map<String, ModelComponentId> theVariables;
			private final ModelValueInstantiator<SettableValue<Instant>> theStart;
			private final ModelValueInstantiator<SettableValue<Instant>> theEnd;
			private final ModelValueInstantiator<SettableValue<Boolean>> isActive;
			private final List<ProcessActionData.Instantiator> theActions;

			Instantiator(Interpreted interpreted) {
				theProcess = interpreted.getDefinition().getProcess();
				theProcessIndex = interpreted.getDefinition().getProcessIndex();
				theModels = interpreted.getModels() == null ? null : interpreted.getModels().instantiate();
				theVariables = interpreted.getDefinition().theVariables;
				theStart = interpreted.getStart() == null ? null : interpreted.getStart().instantiate();
				theEnd = interpreted.getEnd() == null ? null : interpreted.getEnd().instantiate();
				isActive = interpreted.isActive() == null ? null : interpreted.isActive().instantiate();
				List<ProcessActionData.Instantiator> actions = new ArrayList<>(interpreted.getActions().size());
				for (ProcessActionData action : interpreted.getActions()) {
					actions.add(action.instantiate());
					if (action.getAction().getError() != null && theProcess.getError() == null) {
						error(theProcess, action.getAction().getError(), true);
					}
				}
				theActions = Collections.unmodifiableList(actions);
			}

			public Process getProcess() {
				return theProcess;
			}

			public int getProcessIndex() {
				return theProcessIndex;
			}

			public ModelValueInstantiator<SettableValue<Instant>> getStart() {
				return theStart;
			}

			public ModelValueInstantiator<SettableValue<Instant>> getEnd() {
				return theEnd;
			}

			public ModelValueInstantiator<SettableValue<Boolean>> isActive() {
				return isActive;
			}

			public List<ProcessActionData.Instantiator> getActions() {
				return theActions;
			}

			public void instantiate() {
				if (theModels != null) {
					theModels.instantiate();
				}

				if (theStart != null) {
					theStart.instantiate();
				}
				if (theEnd != null) {
					theEnd.instantiate();
				}
				if (isActive != null) {
					isActive.instantiate();
				}

				for (ProcessActionData.Instantiator action : theActions) {
					action.instantiate();
				}
			}

			public Instance create(ModelSetInstance models) {
				try {
					models = theModels == null ? null : theModels.wrap(models);
				} catch (ModelInstantiationException e) {
					error(theProcess, e.getMessage(), true);
					e.printStackTrace();
					models = null;
				}
				return new Instance(this, models);
			}
		}

		public static class Instance implements Comparable<Instance> {
			private final Process theProcess;
			private final int theProcessIndex;
			private final Map<String, ModelComponentId> theVariables;
			private final Instant theStart;
			private final Instant theEnd;
			private final SettableValue<Boolean> isActive;
			private final List<ProcessActionData.Instance> theActions;

			private Instant theNextRun;

			Instance(Instantiator instantiator, ModelSetInstance models) {
				theProcess = instantiator.getProcess();
				theProcessIndex = instantiator.getProcessIndex();
				theVariables = instantiator.theVariables;

				Instant start = null;
				try {
					start = (instantiator.getStart() == null || models == null) ? null : instantiator.getStart().get(models).get();
				} catch (ModelInstantiationException e) {
					error(theProcess, e.getMessage(), true);
					e.printStackTrace();
				}
				theStart = start;

				Instant end = null;
				try {
					end = (instantiator.getEnd() == null || models == null) ? null : instantiator.getEnd().get(models).get();
				} catch (ModelInstantiationException e) {
					error(theProcess, e.getMessage(), true);
					e.printStackTrace();
				}
				theEnd = end;

				SettableValue<Boolean> active = null;
				try {
					active = (instantiator.isActive() == null || models == null) ? null : instantiator.isActive().get(models);
				} catch (ModelInstantiationException e) {
					error(theProcess, e.getMessage(), true);
					e.printStackTrace();
				}
				isActive = active;

				List<ProcessActionData.Instance> actions = new ArrayList<>(instantiator.getActions().size());
				for (ProcessActionData.Instantiator action : instantiator.getActions()) {
					try {
						actions.add(action.create(models));
					} catch (ModelInstantiationException e) {
						error(theProcess, e.getMessage(), true);
						e.printStackTrace();
					}
				}
				theActions = Collections.unmodifiableList(actions);

				if (theStart != null) {
					theNextRun = theStart;
				} else {
					theNextRun = theProcess.getPlan().getCurrentDate();
				}
				if (theNextRun != null && end != null && theNextRun.compareTo(end) > 0) {
					theNextRun = null;
				}
			}

			public Instant getNextRun() {
				return theNextRun;
			}

			@Override
			public int compareTo(Instance o) {
				int comp = theNextRun.compareTo(o.theNextRun);
				if (comp == 0) {
					comp = Integer.compare(theProcessIndex, o.theProcessIndex);
				}
				return comp;
			}

			public void addInto(CircularArrayList<Instance> sequence) {
				if (theNextRun == null) {
					return;
				}
				int index = Collections.binarySearch(sequence, this);
				// Should always be negative because the process index is distinct
				index = -index - 1;
				sequence.add(index, this);
			}

			public void run(Map<String, SettableValue<Money>> fundBalances, Map<String, Integer> fundIndexes, TimeZone timeZone,
				SimulationResults results, int frame) {
				if (isActive != null && isActive.get()) {
					for (ProcessActionData.Instance action : theActions) {
						if (action != null) {
							action.run(fundBalances, fundIndexes, results, theProcessIndex, frame);
						}
					}
				}

				theNextRun = theProcess.getPeriod().addTo(theNextRun, timeZone);
				if (theEnd != null && theNextRun.compareTo(theEnd) > 0) {
					theNextRun = null;
				}
			}

			@Override
			public String toString() {
				return theProcess.toString();
			}
		}
	}

	public static class ProcessActionData {
		private final ProcessAction theAction;
		private final int theActionIndex;
		private final Fund theFund;
		private final InterpretedValueSynth<SettableValue<?>, SettableValue<Money>> theAmount;

		public ProcessActionData(ProcessAction action, InterpretedExpressoEnv env) {
			theAction = action;
			this.theActionIndex = action.getProcess().getActions().getValues().indexOf(action);
			this.theFund = action.getFund();
			InterpretedValueSynth<SettableValue<?>, SettableValue<Money>> amount = null;
			try {
				if (action.getAmount() != null) {
					amount = action.getAmount().evaluate(MONEY_TYPE, env, 0, ExceptionHandler.thrower2());
				} else {
					error(action, "No amount set", true);
				}
			} catch (ExpressoInterpretationException | TypeConversionException e) {
				error(theAction, e.getMessage(), true);
				e.printStackTrace();
			}
			theAmount = amount;
		}

		public ProcessAction getAction() {
			return theAction;
		}

		public int getActionIndex() {
			return theActionIndex;
		}

		public Fund getFund() {
			return theFund;
		}

		public InterpretedValueSynth<SettableValue<?>, SettableValue<Money>> getAmount() {
			return theAmount;
		}

		public Instantiator instantiate() {
			return new Instantiator(this);
		}

		@Override
		public String toString() {
			return theFund + ": " + theAmount;
		}

		public static class Instantiator {
			private final ProcessAction theAction;
			private final int theActionIndex;
			private final Fund theFund;
			private final ModelValueInstantiator<SettableValue<Money>> theAmount;

			Instantiator(ProcessActionData interpreted) {
				theAction = interpreted.getAction();
				theActionIndex = interpreted.getActionIndex();
				theFund = interpreted.getFund();
				theAmount = interpreted.getAmount() == null ? null : interpreted.getAmount().instantiate();
			}

			public void instantiate() {
				if (theAmount != null) {
					theAmount.instantiate();
				}
			}

			public ProcessAction getAction() {
				return theAction;
			}

			public int getActionIndex() {
				return theActionIndex;
			}

			public Fund getFund() {
				return theFund;
			}

			public ModelValueInstantiator<SettableValue<Money>> getAmount() {
				return theAmount;
			}

			public Instance create(ModelSetInstance models) throws ModelInstantiationException {
				return new Instance(this, models);
			}
		}

		public static class Instance {
			private final ProcessAction theAction;
			private final int theActionIndex;
			private final Fund theFund;
			private final SettableValue<Money> theAmount;

			Instance(Instantiator instantiator, ModelSetInstance models) throws ModelInstantiationException {
				theAction = instantiator.getAction();
				theActionIndex = instantiator.getActionIndex();
				theFund = instantiator.getFund();
				SettableValue<Money> amount = null;
				try {
					if (instantiator.getAmount() != null && models != null) {
						amount = instantiator.getAmount().get(models);
					}
				} catch (ModelInstantiationException e) {
					error(theAction, e.getMessage(), true);
					throw e;
				}
				theAmount = amount;
			}

			public ProcessAction getAction() {
				return theAction;
			}

			public int getActionIndex() {
				return theActionIndex;
			}

			public Fund getFund() {
				return theFund;
			}

			public SettableValue<Money> getAmount() {
				return theAmount;
			}

			public void run(Map<String, SettableValue<Money>> fundBalances, Map<String, Integer> fundIndexes, SimulationResults results,
				int processIndex, int frame) {
				if (theAmount == null) {
					return;
				}
				Money money = theAmount.get();
				if (money.value == 0) {
					return;
				}
				if (frame >= 0 && results != null) {
					results.processAmounts[processIndex][frame] += money.value;
					results.processActionAmounts[processIndex][theActionIndex][frame] += money.value;
					results.fundProcessContributions[fundIndexes.get(theFund.getName())][processIndex][frame] += money.value;
				}

				SettableValue<Money> balance = fundBalances.get(theFund.getName());
				balance.set(new Money(balance.get().value + money.value), null);
				if (DEBUG) {
					System.out.println("\t\t" + theFund.getName() + (money.value < 0 ? " " : " +") + money + " to " + balance.get());
				}
			}
		}
	}
}
