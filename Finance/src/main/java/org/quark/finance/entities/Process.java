package org.quark.finance.entities;

import org.observe.collect.ObservableCollection;
import org.observe.config.ObservableValueSet;
import org.observe.expresso.ObservableExpression;
import org.qommons.TimeUtils.ParsedDuration;

public interface Process extends PlanComponent {
	ObservableExpression getActive();

	Process setActive(ObservableExpression active);

	ObservableExpression getStart();

	Process setStart(ObservableExpression start);

	ObservableExpression getEnd();

	Process setEnd(ObservableExpression end);

	ParsedDuration getPeriod();

	Process setPeriod(ParsedDuration period);

	ObservableValueSet<ProcessVariable> getLocalVariables();

	ObservableValueSet<ProcessAction> getActions();

	ObservableCollection<AssetGroup> getMemberships();
}
