package org.baqery.entities;

import org.observe.util.Identified;
import org.observe.util.NamedEntity;

public interface Ingredient extends NamedEntity, Identified {
	String getDescription();
	Ingredient setDescription(String descrip);

	String getNotes();
	Ingredient setNotes(String notes);
}
