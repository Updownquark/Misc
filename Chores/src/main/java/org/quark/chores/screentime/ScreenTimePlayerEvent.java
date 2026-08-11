package org.quark.chores.screentime;

public class ScreenTimePlayerEvent {
	public String type = "";
	public String player = "";
	public int amount;

	public ScreenTimePlayerEvent(ScreenTimePlayerEvent event) {
		if (event != null) {
			type = event.type;
			player = event.player;
			amount = event.amount;
		}
	}
}
