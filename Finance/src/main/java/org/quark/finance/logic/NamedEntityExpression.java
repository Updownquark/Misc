package org.quark.finance.logic;

import org.observe.expresso.ops.NameExpression;
import org.qommons.Transaction;
import org.qommons.collect.BetterList;
import org.quark.finance.entities.PlanComponent;

public class NamedEntityExpression<E extends PlanComponent> extends NameExpression {
	private static final ThreadLocal<Boolean> PERSISTING = new ThreadLocal<>();

	public static Transaction persist() {
		PERSISTING.set(true);
		return PERSISTING::remove;
	}

	public static boolean isPersisting() {
		return Boolean.TRUE.equals(PERSISTING.get());
	}

	private final E theEntity;
	private final String thePersistencePrefix;

	public NamedEntityExpression(E entity, String persistencePrefix) {
		super(null, BetterList.of(entity.getName()));
		theEntity = entity;
		thePersistencePrefix = persistencePrefix;
	}

	public E getEntity() {
		return theEntity;
	}

	@Override
	public int hashCode() {
		return theEntity.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof NamedEntityExpression && theEntity.equals(((NamedEntityExpression<?>) obj).theEntity);
	}

	@Override
	public String toString() {
		if (isPersisting()) {
			return thePersistencePrefix+theEntity.getId();
		} else {
			return theEntity.getName();
		}
	}
}
