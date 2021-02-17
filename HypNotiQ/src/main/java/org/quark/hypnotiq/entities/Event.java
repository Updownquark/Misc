package org.quark.hypnotiq.entities;

import java.time.Instant;

import org.observe.config.ParentReference;
import org.observe.config.SyncValueSet;
import org.observe.util.NamedEntity;

public interface Event extends NamedEntity {
	@ParentReference
	Note getNote();

	Instant getInitialTime();
	Event setInitialTime(Instant time);

	/**
	 * @return The recurrence interval of this Notification. This value is a string representation of the interval like "1d" or "3mo" or
	 *         "1y". There is no standard representation I know of for variable intervals like months and years, so this must be a String.
	 */
	String getRecurInterval();
	Event setRecurInterval(String recur);

	Instant getEndTime();
	Event setEndTime(Instant end);

	boolean isActive();
	Event setActive(boolean active);

	SyncValueSet<Notification> getNotifications();
}
