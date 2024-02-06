package org.quark.finance.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.EventQueue;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JPanel;
import javax.swing.ToolTipManager;

import org.observe.Observable;
import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.SimpleObservable;
import org.observe.assoc.ObservableMap;
import org.observe.collect.ObservableCollection;
import org.observe.config.*;
import org.observe.expresso.*;
import org.observe.expresso.ObservableModelSet.InterpretedValueSynth;
import org.observe.expresso.ops.BinaryOperatorSet;
import org.observe.expresso.ops.BufferedName;
import org.observe.expresso.ops.NameExpression;
import org.observe.expresso.ops.UnaryOperatorSet;
import org.observe.util.EntityReflector;
import org.observe.util.TypeTokens;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.ObservableCellRenderer;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.PanelPopulation;
import org.observe.util.swing.PanelPopulation.PanelPopulator;
import org.qommons.Colors;
import org.qommons.StringUtils;
import org.qommons.StringUtils.DuplicateName;
import org.qommons.Ternian;
import org.qommons.TimeUtils;
import org.qommons.TimeUtils.DurationComponentType;
import org.qommons.TimeUtils.ParsedDuration;
import org.qommons.Transaction;
import org.qommons.collect.BetterList;
import org.qommons.collect.ElementId;
import org.qommons.io.ErrorReporting;
import org.qommons.io.Format;
import org.qommons.io.LocatedPositionedContent;
import org.qommons.io.SpinnerFormat;
import org.quark.finance.entities.*;
import org.quark.finance.entities.Process;
import org.quark.finance.logic.FinanceFunctions;
import org.quark.finance.logic.Money;
import org.quark.finance.logic.NamedEntityExpression;
import org.quark.finance.logic.PlanSimulation;
import org.quark.finance.logic.PlanSimulation.SimulationResults;

import com.google.common.reflect.TypeToken;

public class Finance extends JPanel {
	private static final UnaryOperatorSet FINANCE_UNARY_OPS = configureFinanceUnaryOps(
			UnaryOperatorSet.standardJava(UnaryOperatorSet.build())).build();
	private static final BinaryOperatorSet FINANCE_BINARY_OPS = configureFinanceBinaryOps(
			BinaryOperatorSet.standardJava(BinaryOperatorSet.build())).build();

	static class PlanData {
		final Plan plan;

		final ObservableMap<Long, PlanVariable> variablesById;
		final ObservableMap<Long, Fund> fundsById;

		final ObservableMap<String, PlanVariable> variablesByName;
		final ObservableMap<String, Fund> fundsByName;

		PlanData(Plan plan) {
			this.plan = plan;

			variablesById = plan.getVariables().getValues().flow()//
					.groupBy(Long.class, PlanVariable::getId, null)//
					.gather()//
					.singleMap(true);
			fundsById = plan.getFunds().getValues().flow()//
					.groupBy(Long.class, Fund::getId, null)//
					.gather()//
					.singleMap(true);

			variablesByName = plan.getVariables().getValues().flow()//
					.groupBy(String.class, PlanVariable::getName, null)//
					.gather()//
					.singleMap(true);
			fundsByName = plan.getFunds().getValues().flow()//
					.groupBy(String.class, Fund::getName, null)//
					.gather()//
					.singleMap(true);
		}
	}

	private final ObservableConfig theConfig;

	private final ObservableValueSet<Plan> thePlans;
	private final ObservableMap<Long, PlanData> thePlanData;

	private final SettableValue<Instant> theStart;
	private final SettableValue<Instant> theEnd;
	private final SettableValue<ParsedDuration> theResolution;
	private final ObservableCollection<PlanSimulation.SimulationResults> theSimResults;
	private final ObservableCollection<PlanItem> theItemSimResults;
	private final SettableValue<BetterList<Object>> theTreeSelection;

	private final InterpretedExpressoEnv theExpressionEnv;

	public Finance(ObservableConfig config) {
		theConfig = config;

		ObservableConfigParseSession configSession = new ObservableConfigParseSession();
		ObservableConfigFormatSet formatSet = new ObservableConfigFormatSet();
		formatSet.withFormat(TypeTokens.get().of(Color.class), ObservableConfigFormat.ofQommonFormat(new Format<Color>() {
			@Override
			public void append(StringBuilder text, Color value) {
				text.append(Colors.toString(value));
			}

			@Override
			public Color parse(CharSequence text) throws ParseException {
				return Colors.parseColor(text.toString());
			}
		}, null));
		formatSet.withFormat(TypeTokens.get().of(ParsedDuration.class), ObservableConfigFormat.ofQommonFormat(new Format<ParsedDuration>() {
			@Override
			public void append(StringBuilder text, ParsedDuration value) {
				if (value != null) {
					text.append(value);
				}
			}

			@Override
			public ParsedDuration parse(CharSequence text) throws ParseException {
				if (text.length() == 0) {
					return null;
				}
				return TimeUtils.parseDuration(text, true, true);
			}
		}, () -> TimeUtils.flexDuration(1, DurationComponentType.Month)));
		ExpressionFormat expFormat = new ExpressionFormat(new JavaExpressoParser(), this, null, null, true);
		formatSet.withFormat(TypeTokens.get().of(ObservableExpression.class), new ObservableConfigFormat<ObservableExpression>() {
			@Override
			public boolean format(ObservableConfigParseSession session, ObservableExpression value, ObservableExpression previousValue,
					ConfigGetter config2, Consumer<ObservableExpression> acceptedValue, Observable<?> until) throws IllegalArgumentException {
				if (value != null) {
					ObservableConfig cfg = config2.getConfig(true, false);
					Process process = getProcess(cfg, configSession);
					Plan plan = process != null ? process.getPlan() : getPlan(cfg, session);
					if (plan != null) {
						value = value.replaceAll(exp -> replacePlanComponents(exp, plan, process, true));
					}
				}
				try (Transaction t = NamedEntityExpression.persist()) {
					config2.getConfig(true, false).setValue(value == null ? null : value.toString());
				}
				return true;
			}

			@Override
			public ObservableExpression parse(ObservableConfigParseContext<ObservableExpression> ctx) throws ParseException {
				ObservableConfig cfg = ctx.getConfig(false, false);
				if (cfg == null || cfg.getValue() == null) {
					return null;
				}
				return expFormat.parse(cfg.getValue());
			}

			@Override
			public boolean isDefault(ObservableExpression value) {
				return value == null;
			}

			@Override
			public void postCopy(ObservableConfig copied) {

			}
		});
		boolean[] ready = new boolean[1];
		ObservableConfigFormat<Fund> fundRefFormat = ObservableConfigFormat.buildReferenceFormat2((cfg, ids) -> {
			Plan plan = getPlan(cfg, configSession);
			long id = ((Number) ids.get(0)).longValue();
			for (Fund fund : plan.getFunds().getValues()) {
				long fundId = fund.getId();
				if (fundId == id) {
					return fund;
				}
			}
			return null;
		})//
				.withField("id", Fund::getId, formatSet.getConfigFormat(TypeTokens.get().LONG, "id"))//
				.withRetrieverReady(() -> ready[0])//
				.build();
		ObservableConfigFormat<AssetGroup> groupRefFormat = ObservableConfigFormat.buildReferenceFormat2((cfg, ids) -> {
			Plan plan = getPlan(cfg, configSession);
			if (plan == null) {
				return null;
			}
			long id = ((Number) ids.get(0)).longValue();
			for (AssetGroup group : plan.getGroups().getValues()) {
				if (group.getId() == id) {
					return group;
				}
			}
			return null;
		})//
				.withField("id", AssetGroup::getId, formatSet.getConfigFormat(TypeTokens.get().LONG, "id"))//
				.withRetrieverReady(() -> ready[0])//
				.build();
		TypeToken<ObservableCollection<AssetGroup>> groupCollType = TypeTokens.get().keyFor(ObservableCollection.class)
				.<ObservableCollection<AssetGroup>> parameterized(AssetGroup.class);
		formatSet.buildEntityFormat(TypeTokens.get().of(Fund.class), efb -> efb//
				.withFieldFormat(Fund::getMemberships, ObservableConfigFormat.ofCollection(groupCollType, groupRefFormat, "groups", "group"))//
				);
		formatSet.buildEntityFormat(TypeTokens.get().of(ProcessAction.class), efb -> efb//
				.withFieldFormat(ProcessAction::getFund, fundRefFormat)//
				);
		formatSet.buildEntityFormat(TypeTokens.get().of(Process.class), efb -> efb//
				.withFieldFormat(Process::getMemberships, ObservableConfigFormat.ofCollection(groupCollType, groupRefFormat, "groups", "group"))//
				);

		SimpleObservable<Void> builtNotifier = new SimpleObservable<>();
		thePlans = config.asValue(Plan.class)//
				.at("plans/plan")//
				.withSession(configSession)//
				.withFormatSet(formatSet)//
				.withBuiltNotifier(builtNotifier)//
				.buildEntitySet(null);
		// Replace all expressions
		thePlanData = thePlans.getValues().flow()//
				.groupBy(Long.class, Plan::getId, null)//
				.withValues(values -> values.transform(PlanData.class, tx -> tx.cache(true).reEvalOnUpdate(false).map(PlanData::new)))//
				.gather()//
				.singleMap(true);
		ready[0] = true;
		builtNotifier.onNext(null);
		for (Plan plan : thePlans.getValues()) {
			for (PlanVariable vbl : plan.getVariables().getValues()) {
				if (vbl.getValue() != null) {
					try {
						vbl.setValue(vbl.getValue().replaceAll(exp -> replacePlanComponents(exp, plan, null, false)));
					} catch (RuntimeException e) {
						System.err.println(plan.getName() + " var " + vbl.getName() + ": " + e.getMessage());
						e.printStackTrace();
					}
				}
			}
			for (Fund fund : plan.getFunds().getValues()) {
				if (fund.getStartingBalance() != null) {
					try {
						fund.setStartingBalance(fund.getStartingBalance().replaceAll(exp -> replacePlanComponents(exp, plan, null, false)));
					} catch (RuntimeException e) {
						System.err.println(plan.getName() + " fund " + fund.getName() + ": " + e.getMessage());
						e.printStackTrace();
					}
				}
			}
			for (Process process : plan.getProcesses().getValues()) {
				if (process.getStart() != null) {
					try {
						process.setStart(process.getStart().replaceAll(exp -> replacePlanComponents(exp, plan, process, false)));
					} catch (RuntimeException e) {
						System.err.println(plan.getName() + " process " + process.getName() + " start: " + e.getMessage());
						e.printStackTrace();
					}
				}
				if (process.getEnd() != null) {
					try {
						process.setEnd(process.getEnd().replaceAll(exp -> replacePlanComponents(exp, plan, process, false)));
					} catch (RuntimeException e) {
						System.err.println(plan.getName() + " process " + process.getName() + " end: " + e.getMessage());
						e.printStackTrace();
					}
				}
				for (ProcessVariable vbl : process.getLocalVariables().getValues()) {
					if (vbl.getValue() != null) {
						try {
							vbl.setValue(vbl.getValue().replaceAll(exp -> replacePlanComponents(exp, plan, process, false)));
						} catch (RuntimeException e) {
							System.err.println(
									plan.getName() + " process " + process.getName() + "  variable " + vbl.getName() + ": " + e.getMessage());
							e.printStackTrace();
						}
					}
				}
				for (ProcessAction action : process.getActions().getValues()) {
					if (action.getAmount() != null) {
						try {
							action.setAmount(action.getAmount().replaceAll(exp -> replacePlanComponents(exp, plan, process, false)));
						} catch (RuntimeException e) {
							System.err.println(plan.getName() + " process " + process.getName() + "  action["
									+ process.getActions().getValues().indexOf(action) + "]: " + e.getMessage());
							e.printStackTrace();
						}
					}
				}
			}
		}

		ObservableCollection<Plan> visiblePlans = thePlans.getValues().flow()//
				.filter(p -> p.isShown() ? null : "Not Shown")//
				.collect();
		ObservableValue<Instant> minStart = visiblePlans.maxBy((p1, p2) -> {
			if (p1.getCurrentDate() == null) {
				if (p2.getCurrentDate() == null) {
					return 0;
				}
				return 1;
			} else if (p2.getCurrentDate() == null) {
				return -1;
			} else {
				return p1.getCurrentDate().compareTo(p2.getCurrentDate());
			}
		}, null, Ternian.TRUE).map(p -> p == null ? null : p.getCurrentDate());
		ObservableValue<Instant> maxEnd = visiblePlans.maxBy((p1, p2) -> {
			if (p1.getGoalDate() == null) {
				if (p2.getGoalDate() == null) {
					return 0;
				}
				return 1;
			} else if (p2.getGoalDate() == null) {
				return -1;
			} else {
				return p1.getGoalDate().compareTo(p2.getGoalDate());
			}
		}, null, Ternian.TRUE).map(p -> p == null ? null : p.getGoalDate());
		theStart = SettableValue.build(Instant.class).withValue(minStart.get()).build();
		theEnd = SettableValue.build(Instant.class).withValue(maxEnd.get()).build();
		minStart.noInitChanges().act(evt -> {
			if (!Objects.equals(evt.getOldValue(), evt.getNewValue())) {
				theStart.set(evt.getNewValue(), evt);
			}
		});
		maxEnd.noInitChanges().act(evt -> {
			if (!Objects.equals(evt.getOldValue(), evt.getNewValue())) {
				theEnd.set(evt.getNewValue(), evt);
			}
		});
		theResolution = SettableValue.build(ParsedDuration.class).withValue(TimeUtils.flexDuration(1, DurationComponentType.Month)).build();

		NonStructuredParser moneyParser = new NonStructuredParser() {
			@Override
			public boolean canParse(TypeToken<?> type, String text) {
				if (text.isEmpty() || text.charAt(0) != '$') {
					return false;
				}
				if (!TypeTokens.get().isAssignable(type, TypeTokens.get().of(Money.class))) {
					return false;
				}
				return true;
			}

			@Override
			public <T> InterpretedValueSynth<SettableValue<?>, ? extends SettableValue<? extends T>> parse(TypeToken<T> type, String text)
					throws ParseException {
				Money value = new Money(Math.round(Double.parseDouble(text.substring(1, text.length())) * 100));
				return (InterpretedValueSynth<SettableValue<?>, ? extends SettableValue<? extends T>>) InterpretedValueSynth
						.literalValue(TypeTokens.get().of(Money.class), value, text);
			}

			@Override
			public String getDescription() {
				return "Money literal parser";
			}

			@Override
			public String toString() {
				return getDescription();
			}
		};
		NonStructuredParser instantParser = new NonStructuredParser() {
			@Override
			public boolean canParse(TypeToken<?> type, String text) {
				if (type.getType() == Instant.class) {
					return true;
				} else if (!TypeTokens.get().isAssignable(type, TypeTokens.get().of(Instant.class))) {
					return false;
				} else {
					try {
						return TimeUtils.parseInstant(text, true, false, null) != null;
					} catch (ParseException e) {
						throw new IllegalStateException("Should not happen", e);
					}
				}
			}

			@Override
			public <T> InterpretedValueSynth<SettableValue<?>, ? extends SettableValue<? extends T>> parse(TypeToken<T> type, String text)
					throws ParseException {
				Instant value = TimeUtils.parseInstant(text, true, true, null).evaluate(Instant::now);
				return (InterpretedValueSynth<SettableValue<?>, ? extends SettableValue<? extends T>>) InterpretedValueSynth
						.literalValue(TypeTokens.get().of(Instant.class), value, text);
			}

			@Override
			public String getDescription() {
				return "Instant literal parser";
			}

			@Override
			public String toString() {
				return getDescription();
			}
		};
		NonStructuredParser durationParser = new NonStructuredParser() {
			@Override
			public boolean canParse(TypeToken<?> type, String text) {
				if (type.getType() == ParsedDuration.class) {
					return true;
				} else if (!TypeTokens.get().isAssignable(type, TypeTokens.get().of(ParsedDuration.class))) {
					return false;
				} else {
					try {
						return TimeUtils.parseDuration(text, true, false) != null;
					} catch (ParseException e) {
						throw new IllegalStateException("Should not happen", e);
					}
				}
			}

			@Override
			public <T> InterpretedValueSynth<SettableValue<?>, ? extends SettableValue<? extends T>> parse(TypeToken<T> type, String text)
					throws ParseException {
				ParsedDuration value = TimeUtils.parseDuration(text, true, true);
				return (InterpretedValueSynth<SettableValue<?>, ? extends SettableValue<? extends T>>) InterpretedValueSynth
						.literalValue(TypeTokens.get().of(ParsedDuration.class), value, text);
			}

			@Override
			public String getDescription() {
				return "Flexible duration literal";
			}

			@Override
			public String toString() {
				return getDescription();
			}
		};

		theExpressionEnv = InterpretedExpressoEnv.INTERPRETED_STANDARD_JAVA.with(ClassView.build()//
				.withWildcardImport("java.lang")//
				.withWildcardImport("java.lang.Math")//
				.withWildcardImport(FinanceFunctions.class.getName())//
				.build())//
				.withOperators(FINANCE_UNARY_OPS, FINANCE_BINARY_OPS)//
				.withErrorReporting(new ErrorReporting.Default(LocatedPositionedContent.of("Quark Finance", null)))//
				.withNonStructuredParser(Money.class, moneyParser)//
				.withNonStructuredParser(Object.class, moneyParser)//
				.withNonStructuredParser(Instant.class, instantParser)//
				.withNonStructuredParser(Object.class, instantParser)//
				.withNonStructuredParser(Duration.class, durationParser)//
				.withNonStructuredParser(Object.class, durationParser)//
				;

		theSimResults = visiblePlans.flow()//
				.refresh(Observable.or(theStart.noInitChanges(), theEnd.noInitChanges(), theResolution.noInitChanges())//
						.filterMap(evt -> !Objects.equals(evt.getOldValue(), evt.getNewValue())))//
				.transform(PlanSimulation.SimulationResults.class, tx -> tx.cache(true).reEvalOnUpdate(true)//
						.build((plan, txValues) -> simulate(plan, txValues.getPreviousResult())))//
				.refreshEach(results -> results == null ? null : results.finished.noInitChanges())//
				.collect();
		theItemSimResults = theSimResults.flow()//
				.flatMap(PlanItem.class, planRes -> ObservableCollection.flattenCollections(PlanItem.class, //
						planRes == null ? null : countTo(planRes.funds.length).flow()//
								.map(PlanItem.class,
										fundIdx -> new PlanItem(planRes, planRes.funds[fundIdx], null, true, planRes.fundBalances[fundIdx]))//
								.collect(), //
								planRes == null ? null : countTo(planRes.funds.length).flow()//
										.flatMap(PlanItem.class, fundIdx -> countTo(planRes.processes.length).flow()//
												.map(PlanItem.class, procIdx -> new PlanItem(planRes, planRes.funds[fundIdx], planRes.processes[procIdx], false,
														planRes.fundProcessContributions[fundIdx][procIdx]))//
												)//
										.collect(), //
										planRes == null ? null : countTo(planRes.processes.length).flow()//
												.map(PlanItem.class,
														procIdx -> new PlanItem(planRes, planRes.processes[procIdx], null, false, planRes.processAmounts[procIdx]))//
												.collect(), //
												planRes == null ? null : countTo(planRes.processes.length).flow()//
														.flatMap(PlanItem.class, procIdx -> countTo(planRes.processActions[procIdx].length).flow()//
																.map(PlanItem.class, actionIdx -> new PlanItem(planRes, planRes.processes[procIdx],
																		planRes.processActions[procIdx][actionIdx], false, planRes.processActionAmounts[procIdx][actionIdx]))//
																)//
														.collect()//
						))//
				.collect();

		theTreeSelection = SettableValue.build((Class<BetterList<Object>>) (Class<?>) BetterList.class).build();
		initComponents();
	}


	PlanSimulation.SimulationResults simulate(Plan plan, SimulationResults previousResults) {
		if (previousResults != null && !previousResults.finished.get()) {
			return previousResults;
		}
		PlanSimulation sim = new PlanSimulation(plan, theExpressionEnv);
		PlanSimulation.Interpreted interpreted;
		try {
			interpreted = sim.interpret(theExpressionEnv);
		} catch (Throwable e) {
			e.printStackTrace();
			return null;
		}
		SimpleObservable<Void> until = new SimpleObservable<>();
		PlanSimulation.Instance instance;
		try {
			PlanSimulation.Instantiator instantiator = interpreted.instantiate();
			instantiator.instantiate();
			instance = instantiator.create(until);
		} catch (ModelInstantiationException e) {
			e.printStackTrace();
			return null;
		}
		PlanSimulation.SimulationResults results = instance.run(//
				theStart.get(), theEnd.get(), theResolution.get());
		until.onNext(null); // TODO Can we do this?
		return results;
	}

	static ObservableCollection<Integer> countTo(int upTo) {
		Integer[] array = new Integer[upTo];
		for (int i = 0; i < upTo; i++) {
			array[i] = i;
		}
		return ObservableCollection.of(Integer.class, array);
	}

	public ObservableValueSet<Plan> getPlans() {
		return thePlans;
	}

	public PlanData getPlanData(Plan plan) {
		return thePlanData.get(plan.getId());
	}

	public ObservableCollection<SimulationResults> getSimulationResults() {
		return theSimResults;
	}

	public ObservableCollection<PlanItem> getItemSimResults() {
		return theItemSimResults;
	}

	public SettableValue<Instant> getStart() {
		return theStart;
	}

	public SettableValue<Instant> getEnd() {
		return theEnd;
	}

	private void initComponents() {
		ToolTipManager.sharedInstance().setInitialDelay(250);
		ToolTipManager.sharedInstance().setDismissDelay(30000);
		ToolTipManager.sharedInstance().setReshowDelay(0);

		ObservableValue<Plan> selectedPlan = theTreeSelection
				.map(path -> (path != null && path.getLast() instanceof Plan) ? (Plan) path.getLast() : null);
		ObservableValue<PlanVariable> selectedVbl = theTreeSelection
				.map(path -> (path != null && path.getLast() instanceof PlanVariable) ? (PlanVariable) path.getLast() : null);
		ObservableValue<Fund> selectedFund = theTreeSelection
				.map(path -> (path != null && path.getLast() instanceof Fund) ? (Fund) path.getLast() : null);
		ObservableValue<Process> selectedProcess = theTreeSelection
				.map(path -> (path != null && path.getLast() instanceof Process) ? (Process) path.getLast() : null);
		ObservableValue<AssetGroup> selectedGroup = theTreeSelection
				.map(path -> (path != null && path.getLast() instanceof AssetGroup) ? (AssetGroup) path.getLast() : null);
		ObservableCollection<PlanItem> fundBalances = theItemSimResults.flow()//
				.filter(item -> item.component instanceof Fund && item.balance ? null : "Not a fund balance")//
				.filter(item -> item.component.isShown() ? null : "Not shown")//
				.collect();
		ObservableCollection<PlanItem> processAmounts = theItemSimResults.flow()//
				.filter(item -> item.component instanceof Process && item.contributor == null ? null : "Not a process amount")//
				.filter(item -> item.component.isShown() ? null : "Not shown")//
				.collect();
		PanelPopulation.populateHPanel(this, new BorderLayout(), null)//
		.addSplit(true, mainSplit -> mainSplit//
				.withSplitProportion(theConfig.asValue(double.class).at("main-vertical-split").buildValue(null))//
				.firstH(new JustifiedBoxLayout(false).mainJustified().crossJustified(),
						p -> p.addSplit(false,
								configSplit -> configSplit.withSplitProportion(theConfig.asValue(double.class).at("main-split").buildValue(null))//
								.firstH(new JustifiedBoxLayout(true).mainJustified().crossJustified(), this::configurePlanTree)//
								.lastH(new JustifiedBoxLayout(true).mainJustified().crossJustified(), p2 -> p2//
										.addComponent(null, new PlanEditor(selectedPlan),
												comp -> comp.fill().fillV().visibleWhen(selectedPlan.map(plan -> plan != null)))//
										.addComponent(null, new VariableEditor(selectedVbl, this),
												comp -> comp.fill().fillV().visibleWhen(selectedVbl.map(time -> time != null)))//
										.addComponent(null, new FundEditor(selectedFund, this),
												comp -> comp.fill().fillV().visibleWhen(selectedFund.map(fund -> fund != null)))//
										.addComponent(null, new ProcessEditor(selectedProcess, this),
												comp -> comp.fill().fillV().visibleWhen(selectedProcess.map(process -> process != null)))//
										.addComponent(null, new AssetGroupEditor(selectedGroup),
												comp -> comp.fill().fillV().visibleWhen(selectedGroup.map(group -> group != null)))//
										)//
								))//
				.lastH(new JustifiedBoxLayout(true).mainJustified().crossJustified(), p -> p//
						.addHPanel(null, new JustifiedBoxLayout(false).mainJustified(), p3 -> p3.fill()//
								.addTextField("Start:", theStart, SpinnerFormat.flexDate(theStart, "MMM dd, yyyy", null), null)//
								.addTextField("End:", theEnd, SpinnerFormat.flexDate(theEnd, "MMM dd, yyyy", null), null)//
								.addTextField("Resolution:", theResolution, SpinnerFormat.forAdjustable(TimeUtils::parseDuration), null)//
								).addTabs(tabs -> tabs//
										.withHTab("balances", new JustifiedBoxLayout(true).mainJustified().crossJustified(), p3 -> p3//
												.addComponent(null, new TimelinePanel(fundBalances, theStart, theEnd, false), null)//
												, tab -> tab.setName("Balances")//
												)//
										.withHTab("income", new JustifiedBoxLayout(true).mainJustified().crossJustified(), p3 -> p3//
												.addComponent(null, new TimelinePanel(processAmounts, theStart, theEnd, false), null)//
												, tab -> tab.setName("Income/Expenses")//
												)//
										))//
				)//
		;
	}

	static class ComponentSetPlaceholder {
		public final Plan plan;
		public final String name;

		public ComponentSetPlaceholder(Plan plan, String name) {
			this.plan = plan;
			this.name = name;
		}

		@Override
		public int hashCode() {
			return Objects.hash(plan, name);
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof ComponentSetPlaceholder //
					&& plan == ((ComponentSetPlaceholder) obj).plan//
					&& name.equals(((ComponentSetPlaceholder) obj).name);
		}

		@Override
		public String toString() {
			return name;
		}
	}

	static final StringUtils.DuplicateItemNamer identifiedDuplicates = new StringUtils.DuplicateItemNamer() {
		private final Pattern pattern = Pattern.compile("(.*)(\\d+)");

		@Override
		public DuplicateName detectDuplicate(String name) {
			Matcher m = pattern.matcher(name);
			if (m.matches()) {
				return new DuplicateName(m.group(1), Integer.parseInt(m.group(2)));
			}
			return null;
		}

		@Override
		public void appendDuplicate(StringBuilder name, int suffix) {
			name.append(suffix);
		}
	};

	void configurePlanTree(PanelPopulator<?, ?> p) {
		p.<Object> addTree2(ObservableValue.of("Plans"), parentPath -> {
			Object parent = parentPath.getLast();
			if ("Plans".equals(parent)) {
				return thePlans.getValues();
			} else if (parent instanceof Plan) {
				return ObservableCollection.of(ComponentSetPlaceholder.class, //
						new ComponentSetPlaceholder((Plan) parent, "Variables"), //
						new ComponentSetPlaceholder((Plan) parent, "Funds"), //
						new ComponentSetPlaceholder((Plan) parent, "Processes"), //
						new ComponentSetPlaceholder((Plan) parent, "Groups"));
			} else if (parent instanceof ComponentSetPlaceholder) {
				switch (((ComponentSetPlaceholder) parent).name) {
				case "Variables":
					return ((Plan) parentPath.get(1)).getVariables().getValues();
				case "Funds":
					return ((Plan) parentPath.get(1)).getFunds().getValues();
				case "Processes":
					return ((Plan) parentPath.get(1)).getProcesses().getValues();
				case "Groups":
					return ((Plan) parentPath.get(1)).getGroups().getValues();
				default:
					return ObservableCollection.of(String.class);
				}
			} else {
				return ObservableCollection.of(Object.class);
			}
		}, tree -> tree.fillV()//
				.withSelection(theTreeSelection, false)//
				.withLeafTest(obj -> !(obj instanceof Plan || obj instanceof ComponentSetPlaceholder))//
				.withRender(render -> render.withRenderer(ObservableCellRenderer.<BetterList<Object>, Object> formatted(Object::toString)//
						.decorate((cell, deco) -> {
							if (cell.getCellValue() instanceof VisibleEntity) {
								deco.withForeground(((VisibleEntity) cell.getCellValue()).isShown() ? Color.black : Color.lightGray);
								if (cell.getCellValue() instanceof PlanComponent && ((PlanComponent) cell.getCellValue()).getError() != null) {
									deco.withLineBorder(Color.red, 1, false);
								}
							}
							boolean iconSet = false;
							if (cell.getCellValue() instanceof PlanVariable) {
								PlanVariable var = (PlanVariable) cell.getCellValue();
								if (var.getVariableType() != null) {
									switch (var.getVariableType()) {
									case Number:
										iconSet = true;
										deco.withIcon(ObservableSwingUtils.getFixedIcon(Finance.class, "/icons/Number.png", 16, 16));
										break;
									case Money:
										iconSet = true;
										deco.withIcon(ObservableSwingUtils.getFixedIcon(Finance.class, "/icons/Money.png", 16, 16));
										break;
									case Duration:
										iconSet = true;
										deco.withIcon(ObservableSwingUtils.getFixedIcon(Finance.class, "/icons/Duration.png", 16, 16));
										break;
									case Instant:
										if (!var.isShown() || var.getColor() == null) {
											iconSet = true;
											deco.withIcon(ObservableSwingUtils.getFixedIcon(Finance.class, "/icons/Date.png", 16, 16));
										}
										break;
									default:
										break;
									}
								}
							}
							if (!iconSet && cell.getCellValue() instanceof PlanComponent
									&& ((PlanComponent) cell.getCellValue()).getColor() != null) {
								deco.withImageIcon(16, 16, image -> {
									image.setColor(((PlanComponent) cell.getCellValue()).getColor());
									image.fillRect(0, 0, 16, 16);
								});
							}
						})))//
				.withRender(render -> render.withValueTooltip((path, sel) -> {
					if (sel instanceof ComponentSetPlaceholder) {
						switch (((ComponentSetPlaceholder) sel).name) {
						case "Variables":
							return "Variables that may be used to modify behavior of processes";
						case "Funds":
							return "Holders of monetary value to be deposited into or withdrawn from by processes";
						case "Processes":
							return "A process runs every so often, making deposits to or withdrawals from funds";
						default:
							return null;
						}
					} else if (sel instanceof PlanComponent) {
						return ((PlanComponent) sel).getError();
					} else {
						return null;
					}
				}))//
				.withAction(null, path -> {
					try {
						switch (((ComponentSetPlaceholder) path.getLast()).name) {
						case "Plans":
							Plan newPlan = thePlans.create()//
							.with(Plan::getName,
									StringUtils.getNewItemName(thePlans.getValues(), Plan::getName, "New Plan", StringUtils.SIMPLE_DUPLICATES))//
							.create().get();
							EventQueue.invokeLater(() -> theTreeSelection.set(BetterList.of("Plans", newPlan), null));
							break;
						case "Variables":
							Plan plan = (Plan) path.get(1);
							PlanVariable vbl = plan.getVariables().create()//
									.with(PlanVariable::getName,
											StringUtils.getNewItemName(plan.getVariables().getValues(), PlanVariable::getName, "vbl",
													identifiedDuplicates))//
									.create().get();
							EventQueue.invokeLater(() -> theTreeSelection.set(BetterList.of("Plans", plan, "Variables", vbl), null));
							break;
						case "Funds":
							plan = (Plan) path.get(1);
							Fund fund = plan.getFunds().create()//
									.with(Fund::getName,
											StringUtils.getNewItemName(plan.getFunds().getValues(), Fund::getName, "fund", identifiedDuplicates))//
									.create().get();
							EventQueue.invokeLater(() -> theTreeSelection.set(BetterList.of("Plans", plan, "Funds", fund), null));
							break;
						case "Processes":
							plan = (Plan) path.get(1);
							Process process = plan.getProcesses().create()//
									.with(Process::getName,
											StringUtils.getNewItemName(plan.getProcesses().getValues(), Process::getName, "Process",
													StringUtils.SIMPLE_DUPLICATES))//
									.create().get();
							EventQueue.invokeLater(() -> theTreeSelection.set(BetterList.of("Plans", plan, "Funds", process), null));
							break;
						case "Groups":
							plan = (Plan) path.get(1);
							AssetGroup group = plan.getGroups().create()//
									.with(AssetGroup::getName,
											StringUtils.getNewItemName(plan.getGroups().getValues(), AssetGroup::getName, "fund",
													StringUtils.SIMPLE_DUPLICATES))//
									.create().get();
							EventQueue.invokeLater(() -> theTreeSelection.set(BetterList.of("Plans", plan, "Groups", group), null));
							break;
						}
					} catch (IllegalArgumentException | ValueOperationException e) {
						e.printStackTrace();
						tree.alert("Could not create new " + StringUtils.singularize((String) path.getLast()), //
								e.getMessage());
					}
				}, action -> action.allowForEmpty(false).allowForMultiple(false).allowWhen(path -> {
					if (!(path.getLast() instanceof ComponentSetPlaceholder)) {
						return "Select 'Plans', 'Variables', 'Funds', 'Processes', or 'Groups'";
					} else {
						return null;
					}
				}, null)//
						.displayAsButton(true).displayAsPopup(false).displayWhenDisabled(false)
						.modifyButton(btn -> btn.withIcon(ObservableSwingUtils.class, "icons/add.png", 16, 16))//
						)//
				.withAction(null, path -> {
					if (path.getLast() instanceof Plan) {
						if (!tree.alert("Delete Plan?",
								"Are you sure you want to delete plan '" + ((Plan) path.getLast()).getName() + "'?" + "\nThis cannot be undone.")
								.confirm(true)) {
							return;
						}
						thePlans.getValues().remove(path.getLast());
					} else if (path.getLast() instanceof PlanVariable) {
						PlanVariable vbl = (PlanVariable) path.getLast();
						if (!tree.alert("Delete Variable?", "Are you sure you want to delete variable '" + vbl.getName() + "' of plan "
								+ vbl.getPlan().getName() + "?" + "\nThis cannot be undone.").confirm(true)) {
							return;
						}
						vbl.getPlan().getVariables().getValues().remove(vbl);
					} else if (path.getLast() instanceof Fund) {
						Fund fund = (Fund) path.getLast();
						if (!tree.alert("Delete Fund?", "Are you sure you want to delete fund '" + fund.getName() + "' of plan "
								+ fund.getPlan().getName() + "?" + "\nThis cannot be undone.").confirm(true)) {
							return;
						}
						fund.getPlan().getFunds().getValues().remove(fund);
					} else if (path.getLast() instanceof Process) {
						Process proc = (Process) path.getLast();
						if (!tree.alert("Delete Process?", "Are you sure you want to delete process '" + proc.getName() + "' of plan "
								+ proc.getPlan().getName() + "?" + "\nThis cannot be undone.").confirm(true)) {
							return;
						}
						proc.getPlan().getProcesses().getValues().remove(proc);
					} else if (path.getLast() instanceof AssetGroup) {
						AssetGroup group = (AssetGroup) path.getLast();
						if (!tree.alert("Delete Group?", "Are you sure you want to delete group '" + group.getName() + "' of plan "
								+ group.getPlan().getName() + "?" + "\nThis cannot be undone.").confirm(true)) {
							return;
						}
						group.getPlan().getGroups().getValues().remove(group);
					}
				}, action -> action.allowForEmpty(false).allowForMultiple(false)
						.allowWhen(
								path -> path.getLast() instanceof Plan || path.getLast() instanceof PlanComponent ? null : "Placeholder--cannot delete",
										null)//
						.displayAsButton(true).displayAsPopup(false).displayWhenDisabled(false)//
						.modifyButton(btn -> btn.withIcon(ObservableSwingUtils.class, "icons/remove.png", 16, 16))//
						)//
				.withMultiAction("Move To Top", paths -> move(paths, true),
						action -> action.allowForEmpty(false).allowForMultiple(true).allowWhenMulti(Finance::filterMove, null))//
				.withMultiAction("Move To Bottom", paths -> move(paths, false),
						action -> action.allowForEmpty(false).allowForMultiple(true).allowWhenMulti(Finance::filterMove, null))//
				// .withRemove(paths -> {
				// Object last = paths.get(0).getLast();
				// if (last instanceof Plan) {
				// thePlans.getValues().remove(last);
				// return;
				// }
				// Plan plan = (Plan) paths.get(0).get(1);
				// if (last instanceof PlanVariable) {
				// plan.getVariables().getValues().remove(last);
				// } else if (last instanceof Fund) {
				// plan.getFunds().getValues().remove(last);
				// } else if (last instanceof Process) {
				// plan.getProcesses().getValues().remove(last);
				// } else if (last instanceof AssetGroup) {
				// plan.getGroups().getValues().remove(last);
				// }
				// }, action -> action.allowForMultiple(false)
				// .allowWhen(path -> path.getLast() instanceof VisibleEntity ? null : "Cannot remove placeholder", null))//
				)//
		;
	}

	void move(List<? extends BetterList<Object>> paths, boolean moveUp) {
		Object parent = null;
		ObservableCollection<?> collection = null;
		List<ElementId> elementsToMove = new ArrayList<>(paths.size());
		for (BetterList<Object> path : paths) {
			Object node = path.get(path.size() - 1);
			if (path.size() < 2) {
				continue;
			} else if (node instanceof ComponentSetPlaceholder) {
				continue;
			} else if (parent == null) {
				parent = path.get(path.size() - 2);
			} else if (path.get(path.size() - 2) != parent) {
				return;
			}
			if (node instanceof Plan) {
				collection = thePlans.getValues();
			} else if (node instanceof PlanVariable) {
				collection = ((PlanVariable) node).getPlan().getVariables().getValues();
			} else if (node instanceof Fund) {
				collection = ((Fund) node).getPlan().getFunds().getValues();
			} else if (node instanceof Process) {
				collection = ((Process) node).getPlan().getProcesses().getValues();
			} else if (node instanceof AssetGroup) {
				collection = ((AssetGroup) node).getPlan().getGroups().getValues();
			} else {
				continue;
			}
			elementsToMove.add(((ObservableCollection<Object>) collection).getElement(node, true).getElementId());
		}
		// Looks like we can move everything
		Collections.sort(elementsToMove);
		if (!moveUp) {
			Collections.reverse(elementsToMove);
		}
		ElementId lastMoved = null;
		for (ElementId move : elementsToMove) {
			collection.move(move, moveUp ? lastMoved : null, moveUp ? null : lastMoved, moveUp, null);
			lastMoved = move;
		}
	}

	static String filterMove(List<? extends BetterList<Object>> paths) {
		// Check to see if we can move the things and get the elements we need to move
		Object parent = null;
		for (BetterList<Object> path : paths) {
			Object node = path.get(path.size() - 1);
			if (path.size() < 2) {
				return "Cannot move the root";
			} else if (node instanceof ComponentSetPlaceholder) {
				return "Placeholder--cannot be moved";
			} else if (parent == null) {
				parent = path.get(path.size() - 2);
			} else if (path.get(path.size() - 2) != parent) {
				return "Can only move items under the same parent";
			}
			if (node instanceof Plan) {//
			} else if (node instanceof PlanVariable) {//
			} else if (node instanceof Fund) {//
			} else if (node instanceof Process) {//
			} else if (node instanceof AssetGroup) {//
			} else {
				return "Unrecognized tree node type: " + node.getClass().getName();
			}
		}
		return null;
	}

	private static UnaryOperatorSet.Builder configureFinanceUnaryOps(UnaryOperatorSet.Builder ops) {
		ops.withSymmetric("-", Money.class, m -> new Money(-m.value), "Monetary negation");
		ops.withSymmetric("-", ParsedDuration.class, d -> d.negate(), "Duration negation");
		return ops;
	}

	private static BinaryOperatorSet.Builder configureFinanceBinaryOps(BinaryOperatorSet.Builder ops) {
		TimeZone timeZone = TimeZone.getDefault();
		// Nothing is reversible here, since this app doesn't allow assigning an expression to a value
		return ops//
				// Money operations
				.with("+", Money.class, Money.class, (m1, m2) -> new Money(m1.value + m2.value), null, null, "Monetary addition")//
				.with("-", Money.class, Money.class, (m1, m2) -> new Money(m1.value - m2.value), null, null, "Monetary subtraction")//
				.with("*", Money.class, double.class, (m1, mult) -> new Money(Math.round(m1.value * mult)), null, null,
						"Monetary multiplication")//
				.with2("*", double.class, Money.class, Money.class, (mult, m1) -> new Money(Math.round(m1.value * mult)), null, null,
						"Monetary multiplication")//
				.with2("*", int.class, Money.class, Money.class, (mult, m1) -> new Money(Math.round(m1.value * mult)), null, null,
						"Monetary multiplication")//
				.with("/", Money.class, double.class, (m1, div) -> new Money(Math.round(m1.value / div)), null, null, "Monetary division")//
				// Instant operations
				.with("+", Instant.class, ParsedDuration.class, (t, d) -> d.addTo(t, timeZone), null, null, "Instant addition")//
				.with2("+", ParsedDuration.class, Instant.class, Instant.class, (d, t) -> d.addTo(t, timeZone), null, null, "Instant addition")//
				.with("-", Instant.class, ParsedDuration.class, (t, d) -> d.negate().addTo(t, timeZone), null, null, "Instant subtraction")//
				.with2("-", Instant.class, Instant.class, ParsedDuration.class, (i1, i2) -> TimeUtils.asFlexDuration(TimeUtils.between(i2, i1)),
						null, null, "Instant subtraction")//
				// Duration operations
				.with("+", ParsedDuration.class, ParsedDuration.class, (d1, d2) -> d1.plus(d1), null, null, "Duration addition")//
				.with("-", ParsedDuration.class, ParsedDuration.class, (d1, d2) -> d1.plus(d1.negate()), null, null, "Duration subtraction")//
				.with("*", ParsedDuration.class, double.class, (d, mult) -> d.times(mult), null, null, "Duration multiplication")//
				.with2("*", double.class, ParsedDuration.class, ParsedDuration.class, (mult, d) -> d.times(mult), null, null,
						"Duration multiplication")//
				.with2("*", int.class, ParsedDuration.class, ParsedDuration.class, (mult, d) -> d.times(mult), null, null,
						"Duration multiplication")//
				.with("/", ParsedDuration.class, double.class, (d, div) -> d.times(1 / div), null, null, "Duration division")//
				.with2("/", ParsedDuration.class, ParsedDuration.class, double.class, (d, div) -> d.divide(div), null, null,
						"Duration division")//
				;
	}

	private static final Pattern VBL_PERSISTENCE_PATTERN = Pattern.compile("vbl(\\d+)");
	private static final Pattern FUND_PERSISTENCE_PATTERN = Pattern.compile("fund(\\d+)");
	private static final Pattern PROC_VBL_PERSISTENCE_PATTERN = Pattern.compile("pvbl(\\d+)");

	private static Plan getPlan(ObservableConfig config, ObservableConfigParseSession configSession) {
		Plan plan = null;
		for (ObservableConfig cfg2 = config; plan == null && cfg2 != null; cfg2 = cfg2.getParent()) {
			Object obj = cfg2.getParsedItem(configSession);
			if (obj instanceof Plan) {
				plan = (Plan) obj;
			}
		}
		return plan;
	}

	private static Process getProcess(ObservableConfig config, ObservableConfigParseSession configSession) {
		Process process = null;
		for (ObservableConfig cfg2 = config; process == null && cfg2 != null; cfg2 = cfg2.getParent()) {
			Object obj = cfg2.getParsedItem(configSession);
			if (obj instanceof Process) {
				process = (Process) obj;
			}
		}
		return process;
	}

	public ObservableExpression replacePlanComponents(ObservableExpression exp, Plan plan, Process process, boolean persisting) {
		if (plan == null) {
			return exp;
		}
		if (exp instanceof NamedEntityExpression) {
			return exp;
		} else if (!(exp instanceof NameExpression) || ((NameExpression) exp).getContext() != null) {
			return exp;
		}
		NameExpression nameX = (NameExpression) exp;
		PlanData data = thePlanData.get(plan.getId());
		if (data == null) {
			return exp;
		}
		BetterList<BufferedName> names = nameX.getNames();
		String name = names.getFirst().getName();
		if (persisting) {
			PlanComponent comp = data.variablesByName.get(name);
			if (comp != null) {
				return new NamedEntityExpression<>(comp, "vbl");
			}
			comp = data.fundsByName.get(name);
			if (comp != null) {
				return new NamedEntityExpression<>(comp, "fund");
			}
			if (process != null) {
				for (ProcessVariable vbl : process.getLocalVariables().getValues()) {
					if (name.equals(vbl.getName())) {
						return new NamedEntityExpression<>(vbl, "pvbl");
					}
				}
			}
		} else {
			Matcher m = VBL_PERSISTENCE_PATTERN.matcher(name);
			if (m.matches()) {
				long id = Long.parseLong(m.group(1));
				PlanVariable inst = data.variablesById.get(id);
				if (inst == null) {
					throw new IllegalArgumentException("No such variable found with ID " + id);
				}
				NamedEntityExpression<PlanVariable> newExp = new NamedEntityExpression<>(inst, "vbl");
				return names.size() == 1 ? newExp : new NameExpression(newExp, names.subList(1, names.size()));
			}
			m = FUND_PERSISTENCE_PATTERN.matcher(name);
			if (m.matches()) {
				long id = Long.parseLong(m.group(1));
				Fund fund = data.fundsById.get(id);
				if (fund == null) {
					throw new IllegalArgumentException("No such fund found with ID " + id);
				}
				NamedEntityExpression<Fund> newExp = new NamedEntityExpression<>(fund, "fund");
				return names.size() == 1 ? newExp : new NameExpression(newExp, names.subList(1, names.size()));
			}
			if (process != null) {
				m = PROC_VBL_PERSISTENCE_PATTERN.matcher(name);
				if (m.matches()) {
					long id = Long.parseLong(m.group(1));
					ProcessVariable inst = null;
					for (ProcessVariable vbl : process.getLocalVariables().getValues()) {
						if (vbl.getId() == id) {
							inst = vbl;
							break;
						}
					}
					if (inst == null) {
						throw new IllegalArgumentException(
								"No such local variable found for process " + process.getName() + " with ID " + id);
					}
					NamedEntityExpression<PlanVariable> newExp = new NamedEntityExpression<>(inst, "pvbl");
					return names.size() == 1 ? newExp : new NameExpression(newExp, names.subList(1, names.size()));
				}
			}
		}
		return exp;
	}

	public static <PC extends PlanComponent> void observeVariableName(ObservableValue<? extends PC> value) {
		// When the name of a variable changes, refresh all fields that refer to it so the rendering is correct
		value.changes().act(valueEvt -> {
			PC comp = valueEvt.getNewValue();
			if (comp == null) {
				return;
			}
			SettableValue<String> nameVal = EntityReflector.observeField(comp, PlanComponent::getName);
			nameVal.noInitChanges().takeUntil(value.noInitChanges().filter(evt -> evt.getOldValue() != evt.getNewValue())).act(nameEvt -> {
				if (Objects.equals(nameEvt.getNewValue(), nameEvt.getOldValue())) {
					return;
				}
				Object search;
				if (comp instanceof ProcessVariable) {
					search = ((ProcessVariable) comp).getProcess();
				} else {
					search = comp.getPlan();
				}
				if (search != null) {
					replaceEntity(search, comp);
				}
			});
		});
	}

	public static void replaceEntity(Object search, PlanComponent comp) {
		if (search instanceof Collection) {
			for (Object element : ((Collection<?>) search)) {
				replaceEntity(element, comp);
			}
		} else if (search instanceof Plan) {
			Plan plan = (Plan) search;
			replaceEntity(plan.getVariables().getValues(), comp);
			replaceEntity(plan.getFunds().getValues(), comp);
			replaceEntity(plan.getProcesses().getValues(), comp);
		} else if (search instanceof PlanVariable) {
			PlanVariable vbl = (PlanVariable) search;
			if (hasEntity(vbl.getValue(), comp)) {
				vbl.setValue(vbl.getValue());
			}
		} else if (search instanceof Fund) {
			Fund fund = (Fund) search;
			if (hasEntity(fund.getStartingBalance(), comp)) {
				fund.setStartingBalance(fund.getStartingBalance());
			}
		} else if (search instanceof Process) {
			Process proc = (Process) search;
			if (hasEntity(proc.getStart(), comp)) {
				proc.setStart(proc.getStart());
			}
			if (hasEntity(proc.getEnd(), comp)) {
				proc.setEnd(proc.getEnd());
			}
			if (hasEntity(proc.getActive(), comp)) {
				proc.setActive(proc.getActive());
			}
			replaceEntity(proc.getLocalVariables().getValues(), comp);
			replaceEntity(proc.getActions().getValues(), comp);
		} else if (search instanceof ProcessAction) {
			ProcessAction action = (ProcessAction) search;
			if (hasEntity(action.getAmount(), comp)) {
				action.setAmount(action.getAmount());
			}
		}
	}

	private static boolean hasEntity(ObservableExpression exp, PlanComponent comp) {
		if (exp == null) {
			return false;
		} else if (exp instanceof NamedEntityExpression && ((NamedEntityExpression<?>) exp).getEntity() == comp) {
			return true;
		}
		for (ObservableExpression child : exp.getComponents()) {
			if (hasEntity(child, comp)) {
				return true;
			}
		}
		return false;
	}

	public static String checkVariableName(String name, PlanComponent comp) {
		if (name == null || name.isEmpty()) {
			return "Name required";
		}
		try {
			ObservableModelSet.JAVA_NAME_CHECKER.checkName(name);
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		if ("CurrentDate".equals(name) || "PlanStart".equals(name)) {
			return "'" + name + "' is reserved";
		}
		for (PlanVariable vbl : comp.getPlan().getVariables().getValues()) {
			if (vbl != comp && Objects.equals(name, vbl.getName())) {
				return "A variable named '" + name + "' already exists in this plan";
			}
		}
		for (Fund fund : comp.getPlan().getFunds().getValues()) {
			if (fund != comp && Objects.equals(name, fund.getName())) {
				return "A fund named '" + name + "' already exists in this plan";
			}
		}
		if (comp instanceof ProcessVariable) {
			for (ProcessVariable vbl : ((ProcessVariable) comp).getProcess().getLocalVariables().getValues()) {
				if (vbl != comp && Objects.equals(name, vbl.getName())) {
					return "A local variable named '" + name + "' already exists in this process";
				}
			}
		}
		return null;
	}

	public static void main(String... clArgs) {
		// Direct3D has issues with swing on some graphics cards. Might put this in the ObServe swing architecture some time.
		System.setProperty("sun.java2d.d3d", "false");
		EventQueue.invokeLater(() -> {
			ObservableSwingUtils.buildUI()//
			.systemLandF()//
			.withConfig("finance")//
			.withTitle("Quark Finance")//
			.build(config -> new Finance(config));
		});
	}
}
