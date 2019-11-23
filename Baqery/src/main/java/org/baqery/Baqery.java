package org.baqery;

import java.io.*;
import java.time.Duration;

import javax.swing.JLabel;
import javax.swing.JPanel;

import org.baqery.entities.Allergen;
import org.baqery.entities.BasicIngredient;
import org.baqery.entities.Ingredient;
import org.baqery.entities.Recipe;
import org.observe.SettableValue;
import org.observe.collect.ObservableCollection;
import org.observe.config.ObservableConfig;
import org.observe.config.ObservableValueSet;
import org.observe.util.TypeTokens;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.PanelPopulation;
import org.observe.util.swing.PanelPopulation.PanelPopulator;
import org.observe.util.swing.TableContentControl;
import org.qommons.QommonsUtils;
import org.qommons.io.Format;
import org.xml.sax.SAXException;

public class Baqery extends JPanel {
	private static final Format<Double> COST_NUMBER_FORMAT = Format.doubleFormat("0.00");
	private static final Format<Double> COST_FORMAT = Format.doubleFormat("$0.00");
	private final ObservableConfig theConfig;

	private final ObservableValueSet<Allergen> theAllergens;
	private final ObservableValueSet<Ingredient> theIngredients;
	private final ObservableCollection<BasicIngredient> theBasicIngredients;
	private final ObservableCollection<Recipe> theRecipes;

	private final SettableValue<BasicIngredient> theSelectedIngredient;
	private final SettableValue<Recipe> theSelectedRecipe;

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
		theBasicIngredients = theIngredients.getValues().flow().filter(BasicIngredient.class).collect();
		theRecipes = theIngredients.getValues().flow().filter(Recipe.class).collect();

		theSelectedIngredient = SettableValue.build(TypeTokens.get().of(BasicIngredient.class)).safe(false).build();
		theSelectedRecipe = SettableValue.build(TypeTokens.get().of(Recipe.class)).safe(false).build();

		initComponents();
	}

	private void initComponents() {
		PanelPopulation.populateVPanel(this, null)//
		.addTabs(tabs -> tabs
			.withHTab("Basic", new JustifiedBoxLayout(false).mainJustified().crossJustified(), this::populateIngredientTab, //
				tab -> tab.setName("Basic Ingredients"))// TODO Tab tooltip
			.withVTab("Recipes", this::populateRecipeTab, tab -> tab.setName("Recipes"))//
			);
	}

	private void populateIngredientTab(PanelPopulator<?, ?> panel) {
		SettableValue<TableContentControl> basicTableControl = SettableValue.build(TypeTokens.get().of(TableContentControl.class))
			.safe(false).build();
		panel.addVPanel(vPanel -> {
			vPanel
			.addTextField(null, basicTableControl, TableContentControl.FORMAT, //
				f -> f.fill().withTooltip(TableContentControl.TABLE_CONTROL_TOOLTIP).modifyEditor(
					tf -> tf.setIcon(ObservableSwingUtils.getFixedIcon(ObservableSwingUtils.class, "/icons/search.png", 16, 16))
					.setEmptyText("Search...")))//
			.addTable(theBasicIngredients, table -> table.withColumn("Name", String.class, Ingredient::getName, column -> {
				column.withValueTooltip((ing, name) -> ing.getDescription())//
				.withMutation(mut -> mut.asText(Format.TEXT)); // TODO Enforce name uniqueness
			}).withSelection(theSelectedIngredient, false));
		}).addVPanel(this::populateIngredientEditor);
	}

	private void populateIngredientEditor(PanelPopulator<?, ?> panel) {
		panel.fill().visibleWhen(theSelectedIngredient.map(i -> i != null))//
		.addTextField("Name:", theSelectedIngredient.map(TypeTokens.get().STRING, Ingredient::getName, BasicIngredient::setName, null),
			Format.TEXT, f -> f.fill())//
		.addTextField("Description:",
			theSelectedIngredient.map(TypeTokens.get().STRING, Ingredient::getDescription, BasicIngredient::setDescription, null),
			Format.TEXT, f -> f.fill())// TODO Make this a text area
		.addHPanel("Volume Cost:", new JustifiedBoxLayout(false).mainJustified(), vCostPanel -> {
			vCostPanel.addComponent(null, new JLabel("$"), null)//
			.addTextField(null, //
				theSelectedIngredient.map(TypeTokens.get().DOUBLE, BasicIngredient::getVolumeCost, BasicIngredient::setVolumeCost,
					null),
				COST_NUMBER_FORMAT, null)//
			.addComponent(null, new JLabel("/"), null)//
			.addTextField(null, theSelectedIngredient.map(BaqeryUtils.VOLUME_AMOUNT_TYPE, BasicIngredient::getVolumeAmount,
				BasicIngredient::setVolumeAmount, null), BaqeryUtils.VOLUME_AMOUNT_FORMAT, null);
		})//
		.addHPanel("Weight Cost:", new JustifiedBoxLayout(false).mainJustified(), vCostPanel -> {
			vCostPanel.addComponent(null, new JLabel("$"), null)//
			.addTextField(null, //
				theSelectedIngredient.map(TypeTokens.get().DOUBLE, BasicIngredient::getMassCost, BasicIngredient::setMassCost,
					null),
				COST_NUMBER_FORMAT, null)//
			.addComponent(null, new JLabel("/"), null)//
			.addTextField(null, theSelectedIngredient.map(BaqeryUtils.MASS_AMOUNT_TYPE, BasicIngredient::getMassAmount,
				BasicIngredient::setMassAmount, null), BaqeryUtils.MASS_AMOUNT_FORMAT, null);
		})//
		.addTextField("Notes:",
			theSelectedIngredient.map(TypeTokens.get().STRING, Ingredient::getNotes, BasicIngredient::setNotes, null), Format.TEXT,
			f -> f.fill())// TODO Make this a text area
		;
	}

	private void populateRecipeTab(PanelPopulator<?, ?> panel) {
		SettableValue<TableContentControl> recipeTableControl = SettableValue.build(TypeTokens.get().of(TableContentControl.class))
			.safe(false).build();
		panel.addTextField(null, recipeTableControl, TableContentControl.FORMAT, //
			f -> f.fill().withTooltip(TableContentControl.TABLE_CONTROL_TOOLTIP)
			.modifyEditor(tf -> tf.setIcon(ObservableSwingUtils.getFixedIcon(ObservableSwingUtils.class, "/icons/search.png", 16, 16))
				.setEmptyText("Search...")))//
		.addTable(theRecipes, table -> table//
			.withColumn("Name", String.class, Ingredient::getName, column -> {
				column.withValueTooltip((ing, name) -> ing.getDescription())//
				.withMutation(mut -> mut.asText(Format.TEXT)); // TODO Enforce name uniqueness
			})//
			.withColumn("Batch Amount", BaqeryUtils.AMOUNT_TYPE, Recipe::getStandardBatch, column -> {
				column.formatText(amt -> BaqeryUtils.AMOUNT_FORMAT.format(amt));
			})//
			.withColumn("Batch Cost", TypeTokens.get().DOUBLE, Recipe::getBatchCost, column -> {
				column.formatText(cost -> "$" + COST_NUMBER_FORMAT.format(cost));
			})//
			.withColumn("Labor Time", TypeTokens.get().of(Duration.class), Recipe::getLaborTime, column -> {
				column.formatText(labor -> QommonsUtils.printDuration(labor, true));
			})//
			.withSelection(theSelectedRecipe, false)//
			).addVPanel(this::populateRecipeEditor)//
		;
	}

	private void populateRecipeEditor(PanelPopulator<?, ?> panel) {
		panel.fill().visibleWhen(theSelectedRecipe.map(r -> r != null))//
		.addTextField("Name:", theSelectedRecipe.map(TypeTokens.get().STRING, Ingredient::getName, Recipe::setName, null), Format.TEXT,
			f -> f.fill())//
		.addTextField("Description:",
			theSelectedRecipe.map(TypeTokens.get().STRING, Ingredient::getDescription, Recipe::setDescription, null), Format.TEXT,
			f -> f.fill())// TODO Make this a text area
		// TODO Ingredients
		.addLabel("Ingredient Cost:", theSelectedRecipe.map(Recipe::getIngredientCost), COST_FORMAT, null)//
		// TODO Hours
		.addLabel("Labor Cost:", theSelectedRecipe.map(Recipe::getLaborCost), COST_FORMAT, null)//
		.addTextField("Procedure:", theSelectedRecipe.map(TypeTokens.get().STRING, Recipe::getProcedure, Recipe::setProcedure, null),
			Format.TEXT, f -> f.fill())// TODO Make this a text area
		.addTextField("Notes:", theSelectedRecipe.map(TypeTokens.get().STRING, Ingredient::getNotes, Recipe::setNotes, null),
			Format.TEXT, f -> f.fill())// TODO Make this a text area
		;
	}
}
