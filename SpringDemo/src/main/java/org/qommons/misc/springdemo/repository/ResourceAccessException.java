package org.qommons.misc.springdemo.repository;

public abstract class ResourceAccessException extends RuntimeException {
	private final String theResourceType;
	private final String theResourceId;

	protected ResourceAccessException(String resourceType, String resourceId, String message) {
		super(message);
		theResourceType = resourceType;
		theResourceId = resourceId;
	}

	public String getResourceType() {
		return theResourceType;
	}

	public String getResourceId() {
		return theResourceId;
	}
}
