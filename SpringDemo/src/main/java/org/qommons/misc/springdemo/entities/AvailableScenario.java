package org.qommons.misc.springdemo.entities;

import java.time.Instant;

import jakarta.persistence.*;

@Entity
@Table(name = "SCENARIOS", indexes = { @Index(columnList = "owner_id") })
public class AvailableScenario {
	private long theId;
	private DemoUser theOwner;
	private Instant theCreated;

	public AvailableScenario(DemoUser owner, long scenarioId) {
		theId = scenarioId;
		theOwner = owner;
		theCreated = Instant.now();
	}

	/** Hibernate constructor */
	@SuppressWarnings("unused")
	private AvailableScenario() {
	}

	@Id
	@GeneratedValue
	public long getId() {
		return theId;
	}

	@SuppressWarnings("unused")
	private void setId(long id) {
		theId = id;
	}

	@ManyToOne(optional = false)
	@JoinColumn(name = "owner_id")
	public DemoUser getOwner() {
		return theOwner;
	}

	public void setOwner(DemoUser owner) {
		theOwner = owner;
	}

	@Column(nullable = false)
	public Instant getCreated() {
		return theCreated;
	}

	public void setCreated(Instant created) {
		theCreated = created;
	}
}
