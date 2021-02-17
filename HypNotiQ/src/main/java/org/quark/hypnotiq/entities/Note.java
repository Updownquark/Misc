package org.quark.hypnotiq.entities;

import java.time.Instant;
import java.util.List;

import org.observe.config.SyncValueSet;
import org.observe.util.Identified;
import org.observe.util.NamedEntity;

public interface Note extends NamedEntity, Identified {
	Instant getModified();
	Note setModified(Instant modified);

	String getContent();
	Note setContent(String content);

	List<Subject> getReferences();

	SyncValueSet<Event> getEvents();
}
