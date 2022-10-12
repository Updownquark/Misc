package org.quark.finance.ui;

import java.time.Instant;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.util.EntityReflector;
import org.qommons.io.SpinnerFormat;
import org.quark.finance.entities.Plan;

public class PlanEditor extends PlanEntityEditor<Plan> {
	public PlanEditor(ObservableValue<Plan> selectedPlan) {
		super(selectedPlan, panel -> {
			SettableValue<Instant> currentDate = SettableValue.flatten(selectedPlan//
				.map(plan -> plan == null ? null : EntityReflector.observeField(plan, Plan::getCurrentDate)));
			SettableValue<Instant> goalDate = SettableValue.flatten(selectedPlan//
				.map(plan -> plan == null ? null : EntityReflector.observeField(plan, Plan::getGoalDate)));
			SettableValue<Boolean> shown = SettableValue.flatten(selectedPlan//
				.map(vbl -> vbl == null ? null : EntityReflector.observeField(vbl, Plan::isShown)));
			panel//
				.addTextField("Current Date:", currentDate, SpinnerFormat.flexDate(Instant::now, "MMM dd, yyyy", null), f -> f.fill())//
				.addTextField("Goal Date:", goalDate, SpinnerFormat.flexDate(Instant::now, "MMM dd, yyyy", null), f -> f.fill())//
				.addCheckField("Show:", shown, null)//
			// TODO Stipple
			;
		});
	}

	@Override
	protected String testName(String newName, Plan currentValue) {
		return null; // No constraints on the plan name
	}
}
