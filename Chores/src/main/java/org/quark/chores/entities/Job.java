package org.quark.chores.entities;

import java.time.Duration;
import java.time.Instant;

import org.observe.collect.ObservableCollection;
import org.observe.util.Identified;
import org.observe.util.NamedEntity;

public interface Job extends Identified, NamedEntity {
	int getDifficulty();
	Job setDifficulty(int difficulty);

	Duration getFrequency();
	Job setFrequency(Duration frequency);

	int getMultiplicity();
	Job setMultiplicity(int multiplicity);

	ObservableCollection<String> getInclusionLabels();
	ObservableCollection<String> getExclusionLabels();

	Instant getLastDone();
	Job setLastDone(Instant lastDone);
}
