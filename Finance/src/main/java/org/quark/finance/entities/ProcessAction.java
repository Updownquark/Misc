package org.quark.finance.entities;

import org.observe.config.ParentReference;
import org.observe.expresso.ObservableExpression;

public interface ProcessAction extends PlanComponent {
	@ParentReference
	Process getProcess();

	Fund getFund();

	ProcessAction setFund(Fund fund);

	ObservableExpression getAmount();

	ProcessAction setAmount(ObservableExpression amount);

	@Override
	default boolean isShown() {
		return true;
	}

	@Override
	default VisibleEntity setShown(boolean shown) {
		return this;
	}
}
