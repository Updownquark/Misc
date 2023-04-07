package org.quark.finance.logic;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.observe.expresso.ExpressoEnv;
import org.observe.expresso.ExpressoEvaluationException;
import org.observe.expresso.ExpressoInterpretationException;
import org.observe.expresso.ModelException;
import org.observe.expresso.ModelType.ModelInstanceType;
import org.observe.expresso.ObservableExpression;
import org.observe.expresso.ObservableModelSet.InterpretedValueContainer;
import org.observe.expresso.ObservableModelSet.ValueContainer;
import org.observe.expresso.TypeConversionException;
import org.qommons.Transaction;
import org.quark.finance.entities.PlanComponent;

public class NamedEntityExpression<E extends PlanComponent> implements ObservableExpression {
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
	private final int theOffset;
	private final int theEnd;

	public NamedEntityExpression(E entity, String persistencePrefix, int offset, int end) {
		theEntity = entity;
		thePersistencePrefix = persistencePrefix;
		theOffset = offset;
		theEnd = end;
	}

	@Override
	public int getExpressionOffset() {
		return theOffset;
	}

	@Override
	public int getExpressionEnd() {
		return theEnd;
	}

	public E getEntity() {
		return theEntity;
	}

	@Override
	public List<? extends ObservableExpression> getChildren() {
		return Collections.emptyList();
	}

	@Override
	public ObservableExpression replaceAll(Function<ObservableExpression, ? extends ObservableExpression> replace) {
		return replace.apply(this);
	}

	@Override
	public <M, MV extends M> InterpretedValueContainer<M, MV> evaluate(ModelInstanceType<M, MV> type, ExpressoEnv env)
		throws ExpressoEvaluationException, ExpressoInterpretationException, TypeConversionException {
		try {
			return env.getModels().getValue(theEntity.getName(), type);
		} catch (ModelException e) {
			throw new ExpressoEvaluationException(getExpressionOffset(), getExpressionEnd(), "No such model value: " + theEntity.getName(),
				e);
		}
	}

	@Override
	public <M, MV extends M> ValueContainer<M, MV> evaluateInternal(ModelInstanceType<M, MV> type, ExpressoEnv env)
		throws ExpressoEvaluationException {
		throw new IllegalStateException();
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
