package org.quark.finance.ui;

import java.text.ParseException;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.expresso.ExpressoParser;
import org.observe.expresso.JavaExpressoParser;
import org.observe.expresso.ObservableExpression;
import org.observe.expresso.ObservableModelSet;
import org.observe.util.EntityReflector;
import org.qommons.io.Format;
import org.quark.finance.entities.Fund;
import org.quark.finance.entities.Plan;
import org.quark.finance.entities.PlanVariable;
import org.quark.finance.entities.PlanVariableType;

public class VariableEditor extends PlanComponentEditor<PlanVariable> {
	public VariableEditor(ObservableValue<PlanVariable> selectedVariable, Finance app) {
		super(selectedVariable, panel -> {
			SettableValue<ObservableExpression> value = SettableValue.flatten(selectedVariable//
				.map(vbl -> vbl == null ? null : EntityReflector.observeField(vbl, PlanVariable::getValue)));
			ExpressoParser parser = new JavaExpressoParser();
			Format<ObservableExpression> expFormat = new Format<ObservableExpression>() {
				@Override
				public void append(StringBuilder text, ObservableExpression expression) {
					if (expression != null) {
						text.append(expression);
					}
				}
	
				@Override
				public ObservableExpression parse(CharSequence text) throws ParseException {
					if (text.length() == 0) {
						return null;
					}
					// TODO try to parse simple values first, of type instant, duration, and monetary value
					return parser.parse(text.toString())//
						.replaceAll(exp -> app.replacePlanComponents(exp, selectedVariable.get().getPlan(), null, true));
				}
			};
			panel.addTextField("Value:", value, expFormat, f -> f.fill());
		});
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
