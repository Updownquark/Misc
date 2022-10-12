package org.quark.finance.ui;

import java.util.function.Consumer;

import javax.swing.JPanel;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.util.EntityReflector;
import org.observe.util.swing.PanelPopulation;
import org.observe.util.swing.PanelPopulation.PanelPopulator;
import org.qommons.Nameable;
import org.qommons.io.Format;

public abstract class PlanEntityEditor<E extends Nameable> extends JPanel {
	private final ObservableValue<E> theValue;

	public PlanEntityEditor(ObservableValue<E> value, Consumer<PanelPopulator<?, ?>> postName) {
		theValue = value;
		SettableValue<String> name = SettableValue.flatten(value//
			.map(vbl -> vbl == null ? null : EntityReflector.observeField(vbl, Nameable::getName)))//
			.filterAccept(newName -> {
				E vbl = value.get();
				if (vbl == null) {
					return "No variable selected";
				}
				return testName(newName, vbl);
			});

		PanelPopulator<?, ?> panel = PanelPopulation.populateVPanel(this, null)//
			.addTextField("Name:", name, Format.TEXT, f -> f.fill())//
		;
		postName.accept(panel);
	}

	protected ObservableValue<E> getValue() {
		return theValue;
	}

	protected abstract String testName(String newName, E currentValue);
}
