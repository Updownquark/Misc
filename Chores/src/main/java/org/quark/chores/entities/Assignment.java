package org.quark.chores.entities;

import java.time.Instant;

import org.observe.config.SyncValueSet;

public interface Assignment {
	Instant getDate();

	SyncValueSet<AssignedJob> getAssignments();
}
