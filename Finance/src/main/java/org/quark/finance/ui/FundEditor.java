package org.quark.finance.ui;

import java.awt.Color;
import java.text.ParseException;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.collect.ObservableCollection;
import org.observe.expresso.ExpressoParser;
import org.observe.expresso.JavaExpressoParser;
import org.observe.expresso.ObservableExpression;
import org.observe.expresso.ObservableModelSet;
import org.observe.util.EntityReflector;
import org.observe.util.swing.PanelPopulation;
import org.qommons.io.Format;
import org.quark.finance.entities.Fund;
import org.quark.finance.entities.Plan;
import org.quark.finance.entities.PlanVariable;
import org.quark.finance.entities.Process;

public class FundEditor extends PlanComponentEditor<Fund> {
	public FundEditor(ObservableValue<Fund> selectedFund, Finance app) {
		super(selectedFund, panel -> {
			SettableValue<ObservableExpression> startingBalance = SettableValue.flatten(selectedFund//
				.map(vbl -> vbl == null ? null : EntityReflector.observeField(vbl, Fund::getStartingBalance)));
			SettableValue<Boolean> sink = SettableValue.flatten(selectedFund//
				.map(vbl -> vbl == null ? null : EntityReflector.observeField(vbl, Fund::isDumpedAfterFrame)));
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
					// TODO try to parse a simple monetary value first
					return parser.parse(text.toString())//
						.replaceAll(exp -> app.replacePlanComponents(exp, selectedFund.get().getPlan(), null, true));
				}
			};
			String noneGroup = "None";
			ObservableValue<String> memberships = ObservableValue.flatten(selectedFund//
				.map(fund -> fund == null ? null : fund.getMemberships().reduce(noneGroup, (str, g) -> {
					if (str == noneGroup) {
						str = "";
					} else {
						str += ", ";
					}
					str += g.getName();
					return str;
				})));
			panel//
				.addTextField("Starting Balance:", startingBalance, expFormat, f -> f.fill())//
				.addCheckField("Sink:", sink,
					f -> f.withTooltip("Whether the balance of this fund is reset to zero after each frame of the simulation"))//
				.addLabel("In Groups:", memberships, Format.TEXT, null)//
			;
		});
		ObservableCollection<PlanItem> fund = app.getItemSimResults().flow()//
			.refresh(selectedFund.noInitChanges())//
			.filter(item -> item.component == selectedFund.get() && item.contributor == null ? null : "Not this fund")//
			.collect();
		ObservableCollection<PlanItem> fundContributions = app.getItemSimResults().flow()//
			.refresh(selectedFund.noInitChanges())//
			.filter(item -> item.component == selectedFund.get() && item.contributor instanceof Process ? null
				: "Not a contributor for the selected fund")//
			.filter(item -> anyNonZero(item.values) ? null : "No contribution")//
			.collect();

		PanelPopulation.populateVPanel(this, null)//
			.addComponent(null, new TimelinePanel(fund, app.getStart(), app.getEnd(), false),
				f -> f.fill().fillV().decorate(deco -> deco.withTitledBorder("Balance", Color.black)))//
			.addComponent(null, new TimelinePanel(fundContributions, app.getStart(), app.getEnd(), true),
				f -> f.fill().fillV().decorate(deco -> deco.withTitledBorder("Contributors", Color.black)))//
		;
		/*TODO Process contributions table:
		 	* name (link)
		 	* period
		 	* contribution (amount)
		 */
	}

	static boolean anyNonZero(long[] values) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] != 0) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected String testName(String newName, Fund currentValue) {
		try {
			ObservableModelSet.JAVA_NAME_CHECKER.checkName(newName);
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		Plan plan = currentValue.getPlan();
		for (PlanVariable vbl2 : plan.getVariables().getValues()) {
			if (vbl2.getName().equals(newName)) {
				return "A fund may not share a name with a variable in its plan";
			}
		}
		for (Fund fund : plan.getFunds().getValues()) {
			if (fund == currentValue) {
				continue;
			} else if (fund.getName().equals(newName)) {
				return "Another fund named '" + newName + "' already exists in this plan";
			}
		}
		return null;
	}

	@Override
	protected ObservableValue<String> isShowEnabled() {
		return SettableValue.ALWAYS_ENABLED;
	}
}
