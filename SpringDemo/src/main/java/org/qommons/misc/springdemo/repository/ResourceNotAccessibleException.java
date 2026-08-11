package org.qommons.misc.springdemo.repository;

public class ResourceNotAccessibleException extends RuntimeException {
	private final String theResourceType;
	private final String theResourceId;

	public ResourceNotAccessibleException(String resourceType, String scenarioId) {
		super("The requested " + resourceType + " does not exist or is not accessible");
		theResourceType = resourceType;
		theResourceId = scenarioId;
	}

	public String getResourceType() {
		return theResourceType;
	}

	public String getResourceId() {
		return theResourceId;
	}
}
