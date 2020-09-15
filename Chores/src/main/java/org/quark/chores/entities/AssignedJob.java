package org.quark.chores.entities;

import org.observe.config.ParentReference;

public interface AssignedJob {
	@ParentReference
	Assignment getAssignment();
	Worker getWorker();
	Job getJob();

	int getCompletion();
	AssignedJob setCompletion(int completion);
}
