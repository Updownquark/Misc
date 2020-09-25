package org.quark.chores.entities;

import org.observe.assoc.ObservableMap;
import org.observe.collect.ObservableCollection;
import org.observe.util.Identified;
import org.observe.util.NamedEntity;

public interface Worker extends Identified, NamedEntity {
	int getAbility();
	Worker setAbility(int ability);

	long getExcessPoints();
	Worker setExcessPoints(long excessPoints);

	int getLevel();
	Worker setLevel(int level);

	ObservableCollection<String> getLabels();

	ObservableMap<Job, Integer> getJobPreferences();
}
