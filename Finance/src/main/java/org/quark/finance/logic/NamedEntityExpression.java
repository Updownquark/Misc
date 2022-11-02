package org.quark.finance.logic;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.observe.expresso.ExpressoEnv;
import org.observe.expresso.ModelType.ModelInstanceType;
import org.observe.expresso.ObservableExpression;
import org.observe.expresso.ObservableModelSet.ValueContainer;
import org.qommons.Transaction;
import org.qommons.config.QonfigInterpretationException;
import org.quark.finance.entities.PlanComponent;

import com.google.common.reflect.TypeToken;

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

	public NamedEntityExpression(E entity, String persistencePrefix) {
		theEntity = entity;
		thePersistencePrefix = persistencePrefix;
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
	public <M, MV extends M> ValueContainer<M, MV> evaluateInternal(ModelInstanceType<M, MV> type, ExpressoEnv env)
		throws QonfigInterpretationException {
		return env.getModels().getValue(theEntity.getName(), type);
	}

	@Override
	public <P1, P2, P3, T> MethodFinder<P1, P2, P3, T> findMethod(TypeToken<T> targetType, ExpressoEnv env)
		throws QonfigInterpretationException {
		throw new QonfigInterpretationException("Named entity cannot be evaluated as a method");
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
