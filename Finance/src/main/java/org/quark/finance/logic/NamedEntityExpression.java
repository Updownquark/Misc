package org.quark.finance.logic;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.observe.expresso.ExpressoEnv;
import org.observe.expresso.ExpressoEvaluationException;
import org.observe.expresso.ExpressoInterpretationException;
import org.observe.expresso.ModelException;
import org.observe.expresso.ModelType;
import org.observe.expresso.ModelType.ModelInstanceType;
import org.observe.expresso.ModelTypes;
import org.observe.expresso.ObservableExpression;
import org.observe.expresso.ObservableModelSet.InterpretedValueSynth;
import org.observe.expresso.ObservableModelSet.ModelValueSynth;
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

	public NamedEntityExpression(E entity, String persistencePrefix) {
		theEntity = entity;
		thePersistencePrefix = persistencePrefix;
	}

	@Override
	public ModelType<?> getModelType(ExpressoEnv env) {
		return ModelTypes.Value;
	}

	@Override
	public int getExpressionLength() {
		return theEntity.getName().length();
	}

	@Override
	public int getChildOffset(int childIndex) {
		throw new IndexOutOfBoundsException(childIndex + " of 0");
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
	public <M, MV extends M> InterpretedValueSynth<M, MV> evaluate(ModelInstanceType<M, MV> type, ExpressoEnv env, int expressionOffset)
		throws ExpressoEvaluationException, ExpressoInterpretationException, TypeConversionException {
		try {
			return env.getModels().getValue(theEntity.getName(), type);
		} catch (ModelException e) {
			throw new ExpressoEvaluationException(0, getExpressionLength(), "No such model value: " + theEntity.getName(), e);
		}
	}

	@Override
	public <M, MV extends M> ModelValueSynth<M, MV> evaluateInternal(ModelInstanceType<M, MV> type, ExpressoEnv env, int expressionOffset)
		throws ExpressoEvaluationException, ExpressoInterpretationException {
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
