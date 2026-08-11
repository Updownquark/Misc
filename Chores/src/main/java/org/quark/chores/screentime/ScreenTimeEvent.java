package org.quark.chores.screentime;

import java.util.LinkedHashSet;
import java.util.Set;

import org.observe.collect.ObservableCollection;
import org.qommons.StringUtils;

public class ScreenTimeEvent implements Comparable<ScreenTimeEvent> {
	public int day;
	public String game = "";
	public int amount;
	public boolean isAmountSet;
	public boolean flagged;
	private final long created = System.currentTimeMillis();
	public final ObservableCollection<ScreenTimePlayerEvent> players = ObservableCollection.create();

	public ScreenTimeEvent(ScreenTimeEvent copy) {
		if (copy != null) {
			this.day = copy.day;
			this.game = copy.game;
		}
		players.add(new ScreenTimePlayerEvent(null));
	}

	public String getPlayers() {
		Set<String> set = new LinkedHashSet<>();
		for (ScreenTimePlayerEvent evt : players)
			set.add(evt.player);
		return StringUtils.print(",", set, s -> s).toString();
	}

	public int getTotalAmount() {
		int amount = 0;
		for (ScreenTimePlayerEvent evt : players)
			amount += evt.amount;
		return amount;
	}

	@Override
	public int compareTo(ScreenTimeEvent o) {
		int comp = Integer.compare(day, o.day);
		if (comp == 0)
			comp = Long.compare(created, o.created);
		return comp;
	}
}
