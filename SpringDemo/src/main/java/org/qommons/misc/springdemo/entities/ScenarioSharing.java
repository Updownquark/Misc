package org.qommons.misc.springdemo.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "SCENARIO_SHARING",
indexes = { //
	@Index(columnList = "scenario"), //
	@Index(columnList = "user_id"), //
	@Index(columnList = "scenario, user_id"), //
	@Index(columnList = "scenario, role") //
})
public class ScenarioSharing {
	private long theId;
	private AvailableScenario theScenario;
	private DemoUser theUser;
	private String theRole;

	private ResourceAccessType theAccess;

	public ScenarioSharing(AvailableScenario scenario, DemoUser user) {
		theScenario = scenario;
		theUser = user;
	}

	public ScenarioSharing(AvailableScenario scenario, String role) {
		theScenario = scenario;
		theRole = role;
	}

	/** Hibernate constructor */
	@SuppressWarnings("unused")
	private ScenarioSharing() {}

	@Id
	@GeneratedValue
	public long getId() {
		return theId;
	}

	@SuppressWarnings("unused")
	private void setId(long id) {
		theId = id;
	}

	@ManyToOne
	@JoinColumn(name = "scenario", nullable = false)
	public AvailableScenario getScenario() {
		return theScenario;
	}

	@SuppressWarnings("unused")
	private void setScenario(AvailableScenario scenario) {
		theScenario = scenario;
	}

	@ManyToOne
	@JoinColumn(name = "user_id", nullable = true)
	public DemoUser getUser() {
		return theUser;
	}

	@SuppressWarnings("unused")
	private void setUser(DemoUser user) {
		theUser = user;
	}

	@Column(nullable = true)
	public String getRole() {
		return theRole;
	}

	public void setRole(String role) {
		theRole = role;
	}

	@Enumerated(EnumType.ORDINAL)
	@Column(nullable = false)
	public ResourceAccessType getAccess() {
		return theAccess;
	}

	public void setAccess(ResourceAccessType access) {
		theAccess = access;
	}
}
