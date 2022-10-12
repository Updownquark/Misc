package org.quark.finance.entities;

import org.observe.expresso.ObservableExpression;

public interface PlanVariable extends PlanComponent {
	PlanVariableType getVariableType();

	PlanVariable setVariableType(PlanVariableType type);

	ObservableExpression getValue();

	PlanVariable setValue(ObservableExpression value);
}
