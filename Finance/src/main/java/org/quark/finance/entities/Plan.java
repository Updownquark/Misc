package org.quark.finance.entities;

import java.time.Instant;

import org.observe.config.ObservableValueSet;
import org.observe.util.Identified;
import org.observe.util.NamedEntity;

public interface Plan extends NamedEntity, Identified, VisibleEntity {
	Instant getCurrentDate();

	Plan setCurrentDate(Instant currentDate);

	Instant getGoalDate();

	Plan setGoalDate(Instant goalDate);

	int getStippleLength();

	Plan setStippleLength(int stippleLength);

	int getStippleDotLength();

	Plan setStippleDotLength(int dotLength);

	ObservableValueSet<PlanVariable> getVariables();

	ObservableValueSet<Fund> getFunds();

	ObservableValueSet<Process> getProcesses();

	ObservableValueSet<AssetGroup> getGroups();
}
