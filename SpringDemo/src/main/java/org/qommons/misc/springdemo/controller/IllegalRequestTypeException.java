package org.qommons.misc.springdemo.controller;

import java.util.Set;

import org.qommons.StringUtils;

public class IllegalRequestTypeException extends RuntimeException {
	public IllegalRequestTypeException(Set<String> requestTypes) {
		super(createMessage(requestTypes));
	}

	private static String createMessage(Set<String> requestTypes) {
		StringBuilder msg = new StringBuilder("Command type");
		if (requestTypes.size() == 1)
			msg.append(" '").append(requestTypes.iterator().next()).append("' is");
		else {
			msg.append("s ");
			StringUtils.conversational(", ", ", and ").print(requestTypes, StringBuilder::append);
			msg.append(" are");
		}
		msg.append(" not void-returning and cannot be issued to this end point");
		return msg.toString();
	}
}
