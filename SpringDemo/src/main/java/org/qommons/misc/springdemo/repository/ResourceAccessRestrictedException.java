package org.qommons.misc.springdemo.repository;

import org.qommons.StringUtils;
import org.qommons.misc.springdemo.entities.ResourceAccessType;

public class ResourceAccessRestrictedException extends ResourceAccessException {
	private final ResourceAccessType theAllowedAccessType;
	private final ResourceAccessType theRequestedAccessType;

	public ResourceAccessRestrictedException(String resourceType, String resourceId, ResourceAccessType allowedAccessType,
		ResourceAccessType requestedAccessType) {
		super(resourceType, resourceId, getMessage(StringUtils.capitalize(resourceType), allowedAccessType, requestedAccessType));
		theAllowedAccessType = allowedAccessType;
		theRequestedAccessType = requestedAccessType;
	}

	private static String getMessage(String resourceType, ResourceAccessType allowedAccessType, ResourceAccessType requestedAccessType) {
		return switch (requestedAccessType) {
		case View -> throw new IllegalStateException("A NotAccessible exception should have been used");
		case Edit -> resourceType + " is not modifiable";
		case Delete -> resourceType + " cannot be deleted";
		default -> throw new IllegalStateException("What?");
		};
	}

	public ResourceAccessType getAllowedAccessType() {
		return theAllowedAccessType;
	}

	public ResourceAccessType getRequestedAccessType() {
		return theRequestedAccessType;
	}
}
