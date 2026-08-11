package org.qommons.misc.springdemo.controller;

import org.qommons.misc.springdemo.entities.AvailableScenario;

import lombok.NonNull;

public record ApiScenario(long id, @NonNull String name, @NonNull ApiUser owner, boolean canDelete)
implements Comparable<ApiScenario> {

	@Override
	public int compareTo(ApiScenario o) {
		int comp = owner.compareTo(o.owner());
		if (comp == 0)
			comp = name.compareTo(o.name());
		return comp;
	}

	public static ApiScenario fromJPA(AvailableScenario scenario, String name, boolean canDelete) {
		return new ApiScenario(scenario.getId(), name, ApiUser.fromJPA(scenario.getOwner()), canDelete);
	}
}
