package org.quark.hypnotiq.entities;

import org.observe.collect.ObservableCollection;
import org.observe.util.Identified;
import org.observe.util.NamedEntity;

public interface Subject extends NamedEntity, Identified {
	ObservableCollection<Note> getReferences();
}
