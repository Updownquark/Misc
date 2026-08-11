package org.qommons.misc.springdemo.entities;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "USERS")
public class DemoUser {
	private String theId;
	private String theUserName;
	private String theFullName;
	private Instant theLastActive;
	private AvailableScenario theSelectedScenario;

	public DemoUser(String id, String userName, String fullName) {
		theId = id;
		theUserName = userName;
		theFullName = fullName;
		theLastActive = Instant.now();
	}

	/** Hibernate constructor */
	@SuppressWarnings("unused")
	private DemoUser() {
	}

	@Id
	@Column(updatable = false, nullable = false)
	public String getId() {
		return theId;
	}

	@SuppressWarnings("unused")
	private void setId(String id) {
		theId = id;
	}

	@Column(nullable = false)
	public String getUserName() {
		return theUserName;
	}

	public void setUserName(String userName) {
		theUserName = userName;
	}

	public String getFullName() {
		return theFullName;
	}

	public void setFullName(String fullName) {
		theFullName = fullName;
	}

	public Instant getLastActive() {
		return theLastActive;
	}

	public void setLastActive(Instant lastActive) {
		theLastActive = lastActive;
	}

	@ManyToOne(optional = true)
	public AvailableScenario getSelectedScenario() {
		return theSelectedScenario;
	}

	public void setSelectedScenario(AvailableScenario selectedScenario) {
		theSelectedScenario = selectedScenario;
	}
}
