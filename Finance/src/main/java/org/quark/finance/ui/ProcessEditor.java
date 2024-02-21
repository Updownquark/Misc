package org.quark.finance.ui;

import java.awt.Color;
import java.awt.event.MouseEvent;
import java.util.Objects;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.collect.ObservableCollection;
import org.observe.config.ValueOperationException;
import org.observe.expresso.JavaExpressoParser;
import org.observe.expresso.ObservableExpression;
import org.observe.util.EntityReflector;
import org.observe.util.swing.CategoryRenderStrategy;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.ModelCell;
import org.observe.util.swing.ObservableCellRenderer;
import org.observe.util.swing.PanelPopulation;
import org.qommons.TimeUtils;
import org.qommons.TimeUtils.ParsedDuration;
import org.qommons.io.Format;
import org.qommons.io.SpinnerFormat;
import org.quark.finance.entities.Fund;
import org.quark.finance.entities.PlanVariable;
import org.quark.finance.entities.PlanVariableType;
import org.quark.finance.entities.Process;
import org.quark.finance.entities.ProcessAction;
import org.quark.finance.entities.ProcessVariable;

public class ProcessEditor extends PlanComponentEditor<Process> {
	public ProcessEditor(ObservableValue<Process> selectedProcess, Finance app) {
		super(selectedProcess, false, panel -> {
			ExpressionFormat expFormat = new ExpressionFormat(new JavaExpressoParser(), app, () -> selectedProcess.get().getPlan(),
					selectedProcess, false);
			ObservableCollection<Fund> availableFunds = ObservableCollection.flattenValue(//
					selectedProcess.map(process -> process == null ? null : process.getPlan().getFunds().getValues()));
			SettableValue<ParsedDuration> period = SettableValue.flatten(selectedProcess//
					.map(process -> process == null ? null : EntityReflector.observeField(process, Process::getPeriod)));
			SettableValue<ObservableExpression> active = SettableValue.flatten(selectedProcess//
					.map(process -> process == null ? null : EntityReflector.observeField(process, Process::getActive)));
			SettableValue<ObservableExpression> start = SettableValue.flatten(selectedProcess//
					.map(process -> process == null ? null : EntityReflector.observeField(process, Process::getStart)));
			SettableValue<ObservableExpression> end = SettableValue.flatten(selectedProcess//
					.map(process -> process == null ? null : EntityReflector.observeField(process, Process::getEnd)));
			ObservableCollection<ProcessAction> actions = ObservableCollection.flattenValue(selectedProcess//
					.map(process -> process == null ? null : process.getActions().getValues()));
			ObservableCollection<ProcessVariable> localVariables = ObservableCollection.flattenValue(selectedProcess//
					.map(process -> process == null ? null : process.getLocalVariables().getValues()));
			String noneGroup = "None";
			ObservableValue<String> memberships = ObservableValue.flatten(selectedProcess//
					.map(fund -> fund == null ? null : fund.getMemberships().reduce(noneGroup, (str, g) -> {
						if (str == noneGroup) {
							str = "";
						} else {
							str += ", ";
						}
						str += g.getName();
						return str;
					})));
			SettableValue<String> afterUntilBetween = SettableValue.<String> build().withValue("between").build();
			selectedProcess.changes().act(evt -> {
				if (evt.getNewValue() == null) {
					return;
				} else if (evt.getNewValue().getStart() != null) {
					if (evt.getNewValue().getEnd() != null) {
						afterUntilBetween.set("between", evt);
					} else {
						afterUntilBetween.set("after", evt);
					}
				} else if (evt.getNewValue().getEnd() != null) {
					afterUntilBetween.set("until", evt);
				} else {
					afterUntilBetween.set("forever", evt);
				}
			});
			afterUntilBetween.noInitChanges().act(evt -> {
				Process process = selectedProcess.get();
				if (process == null) {
					return;
				}
				switch (evt.getNewValue()) {
				case "after":
					if (process.getEnd() != null) {
						process.setEnd(null);
					}
					break;
				case "until":
					if (process.getStart() != null) {
						process.setStart(null);
					}
					break;
				case "forever":
					if (process.getStart() != null) {
						process.setStart(null);
					}
					if (process.getEnd() != null) {
						process.setEnd(null);
					}
					break;
				}
			});
			panel//
			.addTextField("Active:", active, expFormat, f -> f.fill())//
			.addHPanel("Every ", new JustifiedBoxLayout(false).mainJustified(), p -> p.fill()//
					.addTextField(null, period, SpinnerFormat.forAdjustable(TimeUtils::parseDuration), null)//
					.spacer(2)//
					.addComboField(null, afterUntilBetween, null, "after", "until", "between", "forever")//
					.spacer(2)//
					.addTextField(null, start, expFormat, f -> f//
							.visibleWhen(afterUntilBetween.map(aub -> aub.equals("after") || aub.equals("between"))))//
					.spacer(2)//
					.addLabel(null, "and", f -> f.visibleWhen(afterUntilBetween.map(aub -> aub.equals("between"))))//
					.spacer(2)//
					.addTextField(null, end, expFormat, f -> f//
							.visibleWhen(afterUntilBetween.map(aub -> aub.equals("until") || aub.equals("between"))))//
					)//
			.addTable(localVariables, table -> table.fill().decorate(deco -> deco.withTitledBorder("Local Variables", Color.black))//
					.withAdaptiveHeight(5, 10, 50)//
					.dragAcceptRow(null).dragSourceRow(null)//
					.withColumn("Name", String.class, ProcessVariable::getName, col -> col.withWidths(50, 150, 500)//
							.withMutation(mut -> mut.mutateAttribute((pv, name) -> {
								if (!Objects.equals(name, pv.getName())) {
									pv.setName(name);
									Finance.replaceEntity(pv.getProcess(), pv);
								}
							}).filterAccept((mce, name) -> Finance.checkVariableName(name, mce.get()))//
									.asText(Format.TEXT)))//
					.withColumn("Value", ObservableExpression.class, PlanVariable::getValue, col -> col.withWidths(50, 300, 1000)//
							.withMutation(mut -> mut.mutateAttribute(PlanVariable::setValue).asText(expFormat))//
							.decorate((cell, deco) -> {
								if (cell.getModelValue().getError() != null) {
									deco.withForeground(Color.red);
								}
							}).withCellTooltip(cell -> cell.getModelValue().getError()))//
					.withColumn("Type", PlanVariableType.class, PlanVariable::getVariableType, col -> col.withWidths(10, 30, 40))//
					.withAdd(() -> {
						try {
							return selectedProcess.get().getLocalVariables().create().create().get();
						} catch (ValueOperationException e) {
							e.printStackTrace();
							return null;
						}
					}, null)//
					.withRemove(vars -> selectedProcess.get().getLocalVariables().getValues().removeAll(vars), null)//
					)//
			.addTable(actions, table -> table.fill().decorate(deco -> deco.withTitledBorder("Actions", Color.black))//
					.withAdaptiveHeight(5, 10, 50)//
					.dragAcceptRow(null).dragSourceRow(null)//
					.withNameColumn(ProcessAction::getName, ProcessAction::setName, true, col -> col.withWidths(50, 100, 500))//
					.withColumn("As", Color.class, ProcessAction::getColor, col -> col.withWidths(20, 20, 20)//
							.withRenderer(ObservableCellRenderer.<ProcessAction, Color> formatted(__ -> null).decorate((cell, deco) -> {
								Color color = cell.getCellValue() == null ? Color.black : cell.getCellValue();
								deco.withImageIcon(16, 16, g -> {
									g.setColor(color);
									g.fillRect(0, 0, 16, 16);
								});
							}))//
							.addMouseListener(new CategoryRenderStrategy.CategoryClickAdapter<ProcessAction, Color>() {
								@Override
								public void mouseClicked(ModelCell<? extends ProcessAction, ? extends Color> cell, MouseEvent e) {
									Color newColor = table
											.alert("Select Color For Action",
													"Select the color to use to render '" + cell.getModelValue().getName()
													+ "' in the editor's timeline")//
											.inputColor(false, cell.getCellValue());
									if (newColor != null && newColor != cell.getCellValue()) {
										cell.getModelValue().setColor(newColor);
									}
								}
							}))//
					.withColumn("Fund", Fund.class, ProcessAction::getFund, col -> col.withWidths(50, 100, 350).withMutation(mut -> mut//
							.mutateAttribute(ProcessAction::setFund)//
							.asCombo(f -> f == null ? "" : f.getName(), availableFunds)))//
					.withColumn("Amount", ObservableExpression.class, ProcessAction::getAmount,
							col -> col.withWidths(100, 250, 10000).withMutation(mut -> mut//
									.mutateAttribute(ProcessAction::setAmount)//
									.asText(expFormat))//
							.decorate((cell, deco) -> {
								if (cell.getModelValue().getError() != null) {
									deco.withForeground(Color.red);
								}
							}).withCellTooltip(cell -> cell.getModelValue().getError()))//
					.withAdd(() -> {
						try {
							return selectedProcess.get().getActions().create().create().get();
						} catch (ValueOperationException e) {
							e.printStackTrace();
							return null;
						}
					}, null)//
					.withRemove(selActions -> selectedProcess.get().getActions().getValues().removeAll(selActions), null)//
					)//
			.addLabel("In Groups:", memberships, Format.TEXT, null)//
			;
		});
		ObservableCollection<PlanItem> actionAmounts = app.getItemSimResults().flow()//
				.refresh(selectedProcess.noInitChanges())//
				.filter(item -> item.component == selectedProcess.get() && item.contributor instanceof ProcessAction ? null
						: "Not an action for the selected process")//
				.collect();
		PanelPopulation.populateVPanel(this, null)//
		.addComponent(null, new TimelinePanel(actionAmounts, app.getStart(), app.getEnd(), true), f -> f.fill().fillV())//
		;
		Finance.observeVariableName(selectedProcess);
	}

	@Override
	protected String testName(String newName, Process currentValue) {
		return null; // No constraints on process name
	}

	@Override
	protected ObservableValue<String> isShowEnabled() {
		return SettableValue.ALWAYS_ENABLED;
	}
}
