package org.qommons.misc.springdemo.controller;

import org.qommons.misc.springdemo.repository.ResourceAccessRestrictedException;
import org.qommons.misc.springdemo.repository.ResourceNotAccessibleException;
import org.qommons.misc.springdemo.service.InternalResourceUnavailableException;
import org.qommons.misc.springdemo.service.ScenarioDataMissingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ExceptionHandling {
	@ExceptionHandler(ResourceNotAccessibleException.class)
	public ProblemDetail handleScenarioNotAccessible(ResourceNotAccessibleException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle(StringUtils.capitalize(ex.getResourceType()) + " Not Found Or Accessible");
		problem.setProperty("timestamp", System.currentTimeMillis());
		problem.setProperty("resourceType", ex.getResourceType());
		problem.setProperty("resourceId", ex.getResourceId());
		return problem;
	}

	@ExceptionHandler(IllegalStateException.class)
	public ProblemDetail handleIllegalState(IllegalStateException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
		problem.setTitle("VISTA could not be loaded successfully");
		problem.setProperty("timestamp", System.currentTimeMillis());
		ex.printStackTrace();
		return problem;
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Bad Request Input");
		problem.setProperty("timestamp", System.currentTimeMillis());
		ex.printStackTrace();
		return problem;
	}

	@ExceptionHandler(ResourceAccessRestrictedException.class)
	public ProblemDetail handleScenarioAccessRestricted(ResourceAccessRestrictedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
		problem.setTitle(StringUtils.capitalize(ex.getResourceType()) + " Operation Not Allowed");
		problem.setProperty("timestamp", System.currentTimeMillis());
		problem.setProperty("resourceType", ex.getResourceType());
		problem.setProperty("resourceId", ex.getResourceId());
		problem.setProperty("requestedAccess", ex.getRequestedAccessType());
		problem.setProperty("allowedAccess", ex.getAllowedAccessType());
		return problem;
	}

	@ExceptionHandler(InternalResourceUnavailableException.class)
	public ProblemDetail handleResourceUnavailable(InternalResourceUnavailableException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED, ex.getMessage());
		problem.setTitle("Internal Resource Unavailable");
		problem.setProperty("timestamp", System.currentTimeMillis());
		return problem;
	}

	@ExceptionHandler(IllegalRequestTypeException.class)
	public ProblemDetail handleIllegalRequestType(IllegalRequestTypeException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
		problem.setTitle("Illegal Service Command");
		problem.setProperty("timestamp", System.currentTimeMillis());
		return problem;
	}

	@ExceptionHandler(ScenarioDataMissingException.class)
	public ProblemDetail handleScenarioDataMissing(ScenarioDataMissingException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
		problem.setTitle("Missing Information for Scenario");
		problem.setProperty("timestamp", System.currentTimeMillis());
		problem.setProperty("scenarioId", ex.getScenarioId());
		return problem;
	}
}
