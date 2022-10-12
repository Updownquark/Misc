package org.quark.finance.entities;

import org.observe.collect.ObservableCollection;
import org.observe.expresso.ObservableExpression;

public interface Fund extends PlanComponent {
	ObservableExpression getStartingBalance();

	Fund setStartingBalance(ObservableExpression balance);

	ObservableCollection<AssetGroup> getMemberships();

	boolean isDumpedAfterFrame();

	Fund setDumpedAfterFrame(boolean dump);
}
