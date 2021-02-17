package org.quark.hypnotiq.entities;

import java.time.Instant;

import org.observe.config.ParentReference;
import org.qommons.Nameable;

public interface Notification extends Nameable {
	@ParentReference
	Event getEvent();

	@Override
	Notification setName(String name);

	String getBefore();
	Notification setBefore(String before);

	int getSnoozeCount();
	Notification setSnoozeCount(int snoozeCount);

	Instant getSnoozeTime();
	Notification setSnoozeTime(Instant snoozeTime);

	Instant getLastDismiss();
	Notification setLastDismiss(Instant lastDismiss);
}
