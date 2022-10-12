package org.quark.finance.ui;

import org.quark.finance.entities.PlanComponent;
import org.quark.finance.logic.PlanSimulation.SimulationResults;

public class PlanItem {
	public final SimulationResults results;
	public final PlanComponent component;
	public final PlanComponent contributor;
	public final boolean balance;
	public final long[] values;

	public PlanItem(SimulationResults results, PlanComponent component, PlanComponent contributor, boolean balance, long[] values) {
		this.results = results;
		this.component = component;
		this.contributor = contributor;
		this.balance = balance;
		this.values = values;
	}

	@Override
	public String toString() {
		if (contributor != null) {
			return contributor+"->"+component;
		} else {
			return component.toString();
		}
	}
}