package org.quark.finance.ui;

import java.awt.Color;
import java.util.function.Consumer;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.util.EntityReflector;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.PanelPopulation;
import org.observe.util.swing.PanelPopulation.PanelPopulator;
import org.qommons.io.Format;
import org.quark.finance.entities.PlanComponent;

public abstract class PlanComponentEditor<E extends PlanComponent> extends PlanEntityEditor<E> {
	private static final String COLOR_LABEL = "\u2588\u2588";

	public PlanComponentEditor(ObservableValue<E> value, Consumer<PanelPopulator<?, ?>> postName) {
		super(value, postName);
		ObservableValue<String> isShowable = isShowEnabled();
		SettableValue<Boolean> shown = SettableValue.flatten(value//
			.map(vbl -> vbl == null ? null : EntityReflector.observeField(vbl, PlanComponent::isShown)))//
			.disableWith(isShowable);
		SettableValue<Color> color = SettableValue.flatten(value//
			.map(vbl -> vbl == null ? null : EntityReflector.observeField(vbl, PlanComponent::getColor)))//
			.disableWith(isShowable);//
		SettableValue<String> error = SettableValue.flatten(value//
			.map(vbl -> vbl == null ? null : EntityReflector.observeField(vbl, PlanComponent::getError)));//
		PanelPopulation.populateVPanel(this, null)//
			.addHPanel("Show:", new JustifiedBoxLayout(false), p -> p.fill()//
				.addCheckField(null, shown, null)//
				.addLabel(null, " in ", null)//
				.addLabel(null, color.map(__ -> COLOR_LABEL), Format.TEXT, f -> f//
					.decorate(deco -> {
						deco.withBackground(color.get()).withForeground(color.get());
					})//
					.repaintOn(color.noInitChanges())//
					.onClick(evt -> {
						Color edited = f
							.alert("Select color for '" + value.get().getName() + "'",
								"Select the color to use to draw '" + value.get().getName() + "' on the timelines")//
							.inputColor(false, color.get());
						if (edited != null) {
							color.set(edited, evt);
						}
					}))//
			)//
			.addLabel("Error: ", error, Format.TEXT, f -> f.fill()//
				.visibleWhen(error.map(e -> e != null))//
				.modifyFieldLabel(font -> font.bold().withColor(Color.red))//
				.decorate(deco -> deco.bold().withForeground(Color.red)))//
		;
	}

	protected abstract ObservableValue<String> isShowEnabled();
}
