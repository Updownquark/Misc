package org.baqery;

import java.io.*;
import java.util.Arrays;

import javax.swing.JPanel;

import org.baqery.entities.Allergen;
import org.baqery.entities.BasicIngredient;
import org.baqery.entities.Ingredient;
import org.observe.SettableValue;
import org.observe.config.ObservableConfig;
import org.observe.config.ObservableValueSet;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.PanelPopulation;
import org.xml.sax.SAXException;

public class Baqery extends JPanel {
	private final ObservableConfig theConfig;

	private final ObservableValueSet<Allergen> theAllergens;
	private final ObservableValueSet<Ingredient> theIngredients;

	private final SettableValue<IngredientType> theSelectedIngType;

	public Baqery() {
		theConfig = ObservableConfig.createRoot("baqery");
		String filePath = System.getProperty("baqery.config");
		if (filePath == null)
			filePath = "baqery.xml";
		File configFile = new File(filePath);
		ObservableConfig.XmlEncoding encoding = ObservableConfig.XmlEncoding.DEFAULT;
		if (configFile.exists()) {
			try (FileInputStream in = new FileInputStream(configFile)) {
				ObservableConfig.readXml(theConfig, in, encoding);
			} catch (IOException e) {
				System.err.println("Could not read " + configFile.getAbsolutePath());
				e.printStackTrace();
			} catch (SAXException e) {
				System.err.println("Could not parse " + configFile.getAbsolutePath());
				e.printStackTrace();
			}
		}
		theConfig.persistOnShutdown(config -> {
			try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(configFile)))) {
				ObservableConfig.writeXml(config, out, encoding, "\t");
			}
		}, ex -> {
			System.err.println("Could not save " + configFile.getAbsolutePath());
			ex.printStackTrace();
		});

		theIngredients = theConfig.asValue(Ingredient.class).at("ingredients").buildEntitySet();
		theAllergens = theConfig.asValue(Allergen.class).at("allergens").buildEntitySet();

		theSelectedIngType = theConfig.asValue(IngredientType.class).at("selected-type").buildValue();

		initComponents();
	}

	private void initComponents() {
		PanelPopulation.populateHPanel(this, new JustifiedBoxLayout(false).mainJustified().crossJustified(), null)//
		.addVPanel(leftPanel -> {
			leftPanel.addComboField("Ingredient Type:", theSelectedIngType, Arrays.asList(IngredientType.values()), ingCombo -> {
				ingCombo.withValueTooltip(type -> {
					switch (type) {
					case Basic:
						return "Basic (generally bought) ingredients that are the components of recipes";
					case Recipe:
						return "A combination of ingredients that may themselves be combined into other recipes";
					}
					return null;
				}).fill();
			})//
			.addTable(theIngredients.getValues().flow().refresh(theSelectedIngType.noInitChanges())
				.filter(ing -> (ing instanceof BasicIngredient) == (theSelectedIngType.get() == IngredientType.Basic) ? null
					: "Wrong ingredient type")
						.collect(), tbl -> {
							// TODO
						})//
			;
		})//
		;
	}
}
