package org.qommons.misc.springdemo.controller;

import java.time.Instant;

import org.qommons.misc.springdemo.entities.DemoUser;

import lombok.NonNull;

public record ApiUser(@NonNull String id, @NonNull String userName, String fullName, Instant lastActive) implements Comparable<ApiUser> {
	@Override
	public int compareTo(ApiUser o) {
		int comp = userName.compareTo(o.userName());
		if (comp == 0)
			comp = id.compareTo(o.id());
		return comp;
	}

	public static ApiUser fromJPA(DemoUser user) {
		return new ApiUser(user.getId(), user.getUserName(), user.getFullName(), user.getLastActive());
	}
}
