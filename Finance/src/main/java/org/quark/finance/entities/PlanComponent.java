package org.quark.finance.entities;

import java.awt.Color;
import java.beans.Transient;

import org.observe.config.ParentReference;
import org.observe.util.Identified;
import org.observe.util.NamedEntity;

public interface PlanComponent extends NamedEntity, Identified, VisibleEntity {
	@ParentReference
	Plan getPlan();

	Color getColor();

	PlanComponent setColor(Color color);

	@Transient
	String getError();

	PlanComponent setError(String error);
}
