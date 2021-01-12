package org.quark.hypnotiq.entities;

import java.time.Instant;

import org.observe.config.ParentReference;
import org.observe.util.NamedEntity;

public interface Notification extends NamedEntity {
	@ParentReference
	Note getNote();

	Instant getInitialTime();
	Notification setInitialTime(Instant time);

	/**
	 * @return The recurrence interval of this Notification. This value is a string representation of the interval like "1d" or "3mo" or
	 *         "1y". There is no standard representation I know of for variable intervals like months and years, so this must be a String.
	 */
	String getRecurInterval();
	Notification setRecurInterval(String recur);

	Instant getEndTime();
	Notification setEndTime(Instant end);

	int getSnoozeCount();
	Notification setSnoozeCount(int snoozeCount);

	Instant getSnoozeTime();
	Notification setSnoozeTime(Instant snoozeTime);

	boolean isActive();
	Notification setActive(boolean active);

	Instant getLastAlertTime();
	Notification setLastAlertTime(Instant lastAlertTime);
}
