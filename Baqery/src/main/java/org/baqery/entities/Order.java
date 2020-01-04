package org.baqery.entities;

import org.observe.config.ObservableValueSet;
import org.observe.util.Identified;
import org.observe.util.NamedEntity;

public interface Order extends NamedEntity, Identified {
	boolean isActive();
	Order setActive(boolean active);

	String getNotes();
	Order setNotes(String notes);

	ObservableValueSet<OrderItem> getItems();

	double getPrice();
	Order setPrice(double price);
}
