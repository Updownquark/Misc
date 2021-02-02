package org.quark.hypnotiq.entities;

import java.time.Instant;
import java.util.List;

import org.observe.config.SyncValueSet;
import org.observe.util.Identified;
import org.observe.util.NamedEntity;

public interface Note2 extends NamedEntity, Identified {
	Instant getNoted();

	Instant getModified();
	Note2 setModified(Instant modified);

	String getContent();
	Note2 setContent(String content);

	List<Subject2> getReferences();

	Instant getInitialTime();
	Note2 setInitialTime(Instant time);

	/**
	 * @return The recurrence interval of this Notification. This value is a string
	 *         representation of the interval like "1d" or "3mo" or "1y". There is
	 *         no standard representation I know of for variable intervals like
	 *         months and years, so this must be a String.
	 */
	String getRecurInterval();
	Note2 setRecurInterval(String recur);

	Instant getEndTime();
	Note2 setEndTime(Instant end);

	boolean isActive();
	Note2 setActive(boolean active);

	SyncValueSet<Notification2> getNotifications();
}
