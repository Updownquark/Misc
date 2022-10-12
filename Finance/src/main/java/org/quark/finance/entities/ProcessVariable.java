package org.quark.finance.entities;

import org.observe.config.ParentReference;

public interface ProcessVariable extends PlanVariable {
	@ParentReference
	Process getProcess();
}
