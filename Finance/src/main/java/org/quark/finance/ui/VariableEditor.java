package org.quark.finance.ui;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.expresso.JavaExpressoParser;
import org.observe.expresso.ObservableExpression;
import org.observe.expresso.ObservableModelSet;
import org.observe.util.EntityReflector;
import org.quark.finance.entities.Fund;
import org.quark.finance.entities.Plan;
import org.quark.finance.entities.PlanVariable;
import org.quark.finance.entities.PlanVariableType;

public class VariableEditor extends PlanComponentEditor<PlanVariable> {
	public VariableEditor(ObservableValue<PlanVariable> selectedVariable, Finance app) {
		super(selectedVariable, true, panel -> {
			SettableValue<ObservableExpression> value = SettableValue.flatten(selectedVariable//
				.map(vbl -> vbl == null ? null : EntityReflector.observeField(vbl, PlanVariable::getValue)));
			ExpressionFormat expFormat = new ExpressionFormat(new JavaExpressoParser(), app, () -> selectedVariable.get().getPlan(), null,
				false);
			panel.addTextField("Value:", value, expFormat, f -> f.fill());
		});
		Finance.observeVariableName(selectedVariable);
	}

	@Override
	protected String testName(String newName, PlanVariable currentValue) {
		try {
			ObservableModelSet.JAVA_NAME_CHECKER.checkName(newName);
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		Plan plan = currentValue.getPlan();
		for (PlanVariable vbl2 : plan.getVariables().getValues()) {
			if (vbl2 == currentValue) {
				continue;
			} else if (vbl2.getName().equals(newName)) {
				return "Another variable named '" + newName + "' already exists in this plan";
			}
		}
		for (Fund fund : plan.getFunds().getValues()) {
			if (fund.getName().equals(newName)) {
				return "A variable may not share a name with a fund in its plan";
			}
		}
		return null;
	}

	@Override
	protected ObservableValue<String> isShowEnabled() {
		return ObservableValue.flatten(getValue()//
			.map(vbl -> vbl == null ? null : EntityReflector.observeField(vbl, PlanVariable::getVariableType)//
				.map(vt -> vt == PlanVariableType.Instant ? null : "Only time variables can be shown")));
	}
}
