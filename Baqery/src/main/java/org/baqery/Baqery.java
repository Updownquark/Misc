package org.baqery;

import java.io.*;

import javax.swing.JPanel;

import org.baqery.entities.Allergen;
import org.baqery.entities.Ingredient;
import org.observe.config.ObservableConfig;
import org.observe.config.ObservableValueSet;
import org.observe.util.swing.ObservableSwingUtils;
import org.xml.sax.SAXException;

public class Baqery extends JPanel {
	private final ObservableConfig theConfig;

	private final ObservableValueSet<Allergen> theAllergens;
	private final ObservableValueSet<Ingredient> theIngredients;

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

		initComponents();
	}

	private void initComponents() {
		ObservableSwingUtils.populateFields(this, null)//
		.addTabs(tabs -> tabs.withTabHPanel("ingredients", "box", p -> {
			p.addTable(theIngredients.getValues(), table -> {//
				// TODO
			});
		}, tab -> tab.setName("Ingredients")//
			))//
		;
	}
}
