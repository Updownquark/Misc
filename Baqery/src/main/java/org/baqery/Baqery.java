package org.baqery;

import java.time.Duration;
import java.util.function.Supplier;

import javax.swing.JPanel;

import org.baqery.entities.*;
import org.jscience.physics.amount.Amount;
import org.observe.SettableValue;
import org.observe.collect.ObservableCollection;
import org.observe.config.ObservableConfig;
import org.observe.config.ObservableConfigFormat;
import org.observe.config.ObservableConfigFormatSet;
import org.observe.config.ObservableValueSet;
import org.observe.util.TypeTokens;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.PanelPopulation;
import org.observe.util.swing.PanelPopulation.PanelPopulator;
import org.observe.util.swing.TableContentControl;
import org.qommons.Named;
import org.qommons.StringUtils;
import org.qommons.io.Format;

import com.google.common.reflect.TypeToken;

public class Baqery extends JPanel {
	private static final Format<Double> COST_NUMBER_FORMAT = Format.doubleFormat("0.00");
	private static final Format<Double> COST_FORMAT = Format.doubleFormat("$0.00");
	private final ObservableConfig theConfig;

	private final ObservableValueSet<Allergen> theAllergens;
	private final ObservableValueSet<LaborType> theLaborTypes;
	private final ObservableValueSet<Ingredient> theIngredients;
	private final ObservableValueSet<InventoryItem> theInventory;
	private final ObservableCollection<PurchasedIngredient> thePurchasedIngredients;
	private final ObservableCollection<Recipe> theRecipes;

	private final SettableValue<PurchasedIngredient> theSelectedIngredient;
	private final SettableValue<Recipe> theSelectedRecipe;

	public Baqery(ObservableConfig config) {
		theConfig = config;

		ObservableConfigFormatSet parsing = new ObservableConfigFormatSet();
		theAllergens = theConfig.asValue(Allergen.class).at("allergens").withFormatSet(parsing).buildEntitySet(null);
		theLaborTypes = theConfig.asValue(LaborType.class).at("labor-types").withFormatSet(parsing).buildEntitySet(null);
		ObservableConfigFormat<Amount<?>> amountFormat = ObservableConfigFormat.ofQommonFormat(BaqeryUtils.AMOUNT_FORMAT, () -> null);
		ObservableValueSet<Ingredient>[] ingredients = new ObservableValueSet[1];
		ObservableConfigFormat<Ingredient> ingRefFormat = (ObservableConfigFormat<Ingredient>) ObservableConfigFormat
			.buildReferenceFormat(fields -> ingredients[0].getValues(), () -> null).build();
		ObservableConfigFormat.EntityConfigFormat<IngredientAmount> ingAmountFormat = ObservableConfigFormat
			.buildEntities(TypeTokens.get().of(IngredientAmount.class), parsing)//
			.withFieldFormat(IngredientAmount::getIngredient, ingRefFormat)//
			.withFieldFormat(IngredientAmount::getAmount, amountFormat)//
			.build();
		ObservableConfigFormat<LaborType> laborTypeRefFormat = ObservableConfigFormat
			.buildReferenceFormat(fields -> theLaborTypes.getValues(), () -> theLaborTypes.getValues().peekFirst()).build();
		ObservableConfigFormat.EntityConfigFormat<Labor> laborFormat = ObservableConfigFormat
			.buildEntities(TypeTokens.get().of(Labor.class), parsing)//
			.withFieldFormat(Labor::getType, laborTypeRefFormat).build();
		ObservableConfigFormat.EntityConfigFormat<Recipe> recipeFormat = ObservableConfigFormat
			.buildEntities(TypeTokens.get().of(Recipe.class), parsing)//
			.withFieldFormat(Recipe::getIngredients, ObservableConfigFormat.ofEntitySet(ingAmountFormat, "ingredient"))//
			.withFieldFormat(Recipe::getLabor, ObservableConfigFormat.ofEntitySet(laborFormat, "labor"))//
			.build();
		theIngredients = ingredients[0] = theConfig.asValue(Ingredient.class).at("ingredients").withFormatSet(parsing)
			.asEntity(efb -> efb//
				.withSubType(TypeTokens.get().of(PurchasedIngredient.class), null)//
				.withSubType(TypeTokens.get().of(Recipe.class), rf -> rf.withFormat(recipeFormat).build("recipe"))//
				).buildEntitySet(null);
		theInventory = theConfig.asValue(InventoryItem.class).at("inventory").withFormatSet(parsing)
			.asEntity(efb -> efb//
				.withFieldFormat(InventoryItem::getIngredient,
					ObservableConfigFormat.<Ingredient> buildReferenceFormat(fields -> theIngredients.getValues(), null).build())//
				.withFieldFormat(InventoryItem::getAllergens, //
					ObservableConfigFormat.ofCollection(new TypeToken<ObservableCollection<Allergen>>() {},
						ObservableConfigFormat.<Allergen> buildReferenceFormat(fields -> theAllergens.getValues(), null).build(), parsing,
						"allergens", "allergen"))//
				.withSubType(TypeTokens.get().of(PurchasedInventoryItem.class), null)//
				.withSubType(TypeTokens.get().of(Batch.class), null)//
				).buildEntitySet(null);
		thePurchasedIngredients = theIngredients.getValues().flow().filter(PurchasedIngredient.class).collect();
		theRecipes = theIngredients.getValues().flow().filter(Recipe.class).collect();

		theSelectedIngredient = SettableValue.build(TypeTokens.get().of(PurchasedIngredient.class)).safe(false).build();
		theSelectedRecipe = SettableValue.build(TypeTokens.get().of(Recipe.class)).safe(false).build();

		initComponents();
	}

	private void initComponents() {
		PanelPopulation.populateVPanel(this, null)//
		.addTabs(tabs -> tabs
			.withHTab("Ingredients", new JustifiedBoxLayout(false).mainJustified().crossJustified(), this::populateIngredientTab, //
				tab -> tab.setName("Basic Ingredients"))// TODO Tab tooltip
			// .withVTab("Recipes", this::populateRecipeTab, tab -> tab.setName("Recipes"))//
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
			.addTable(thePurchasedIngredients, table -> table//
				.withColumn("Name", String.class, Ingredient::getName, column -> {
					column.withValueTooltip((ing, name) -> ing.getDescription())//
					.withMutation(mut -> mut.asText(Format.TEXT).filterAccept((ing, n) -> {
						if (StringUtils.isUniqueName(theIngredients.getValues(), Named::getName, n, (Ingredient) ing.get()))
							return null;
						else
							return "An ingredient named " + n + " already exists";
					}));
				})//
				.withColumn("Description", String.class, Ingredient::getDescription, null)//
				.withColumn("Notes", String.class, Ingredient::getNotes, null)//
				.withColumn("Used In", String.class, this::getUses, null)//
				.withSelection(theSelectedIngredient, false));
		}).addVPanel(this::populateIngredientEditor);
	}

	private void populateIngredientEditor(PanelPopulator<?, ?> panel) {
		ObservableCollection<IngredientAmount> recipeIngredients = ObservableCollection.flattenValue(//
			theSelectedIngredient.map(ing -> ing instanceof Recipe ? ((Recipe) ing).getIngredients().getValues() : null));
		ObservableCollection<Labor> recipeLabor = ObservableCollection.flattenValue(//
			theSelectedIngredient.map(ing -> ing instanceof Recipe ? ((Recipe) ing).getLabor().getValues() : null));
		Supplier<ObservableValueSet<? extends Labor>> laborSet = () -> ((Recipe) theSelectedIngredient.get()).getLabor();
		Format<Labor> laborFormat = BaqeryUtils.laborFormat(laborSet, theLaborTypes.getValues());
		panel.fill().visibleWhen(theSelectedIngredient.map(i -> i != null))//
		.addTextField("Name:",
			theSelectedIngredient.asFieldEditor(TypeTokens.get().STRING, Ingredient::getName, PurchasedIngredient::setName, null),
			Format.TEXT, f -> f.fill())//
		.addTextArea("Description:",
			theSelectedIngredient.asFieldEditor(TypeTokens.get().STRING, Ingredient::getDescription,
				PurchasedIngredient::setDescription, null),
			Format.TEXT, f -> f.fill())//
		.addTextArea("Notes:",
			theSelectedIngredient.asFieldEditor(TypeTokens.get().STRING, Ingredient::getNotes, PurchasedIngredient::setNotes, null),
			Format.TEXT, f -> f.fill())//
		.addTable(recipeIngredients,
			ingTable -> ingTable.fill().visibleWhen(theSelectedIngredient.map(ing -> ing instanceof Recipe))//
			.withColumn("Ingredient", Ingredient.class, IngredientAmount::getIngredient,
				ingCol -> ingCol.withMutation(ingMut -> ingMut.asCombo(Ingredient::getName, theIngredients.getValues())))//
			.withColumn("Amount", (Class<Amount<?>>) (Class<?>) Amount.class, IngredientAmount::getAmount,
				amtCol -> amtCol.formatText(amt -> BaqeryUtils.AMOUNT_FORMAT.format(amt))
				.withMutation(mut -> mut.asText(BaqeryUtils.AMOUNT_FORMAT))))//
		.addTextField("Standard Batch:", theSelectedIngredient.<Amount<?>> asFieldEditor(BaqeryUtils.AMOUNT_TYPE,
			ing -> ing instanceof Recipe ? ((Recipe) ing).getStandardBatch() : null,
				(ing, amt) -> ((Recipe) ing).setStandardBatch(amt), null),
			BaqeryUtils.AMOUNT_FORMAT, f -> f.fill().visibleWhen(theSelectedIngredient.map(ing -> ing instanceof Recipe)))//
		.addList(recipeLabor,
			laborList -> laborList.fill().visibleWhen(theSelectedIngredient.map(ing -> ing instanceof Recipe))//
			.render(laborCol -> laborCol//
				.formatText(labor -> laborFormat.format(labor))//
				.withAddRow(() -> laborSet.get().create().with(Labor::getTime, Duration.ofHours(1))
					.with(Labor::getType, getUnusedType(laborSet.get())).create().get(), labor -> labor, null)//
				.withMutation(m -> m.asText(laborFormat)))//
			.withRemove(labors -> ((Recipe) theSelectedIngredient.get()).getLabor().getValues().removeAll(labors), null)//
			).addTextArea("Procedure:", theSelectedIngredient.asFieldEditor(TypeTokens.get().STRING, ing -> ((Recipe) ing).getProcedure(), (ing, p) -> ((Recipe) ing).setProcedure(p), null), Format.TEXT, f -> f.fill().visibleWhen(theSelectedIngredient.map(ing -> ing instanceof Recipe))//
				);
	}

	private LaborType getUnusedType(ObservableValueSet<? extends Labor> labor) {
		for (LaborType type : theLaborTypes.getValues()) {
			boolean found = false;
			for (Labor lbr : labor.getValues()) {
				if (lbr.getType() == type) {
					found = true;
					break;
				}
			}
			if (!found)
				return type;
		}
		return theLaborTypes.getValues().getFirst();
	}

	// private void populateRecipeTab(PanelPopulator<?, ?> panel) {
	// SettableValue<TableContentControl> recipeTableControl = SettableValue.build(TypeTokens.get().of(TableContentControl.class))
	// .safe(false).build();
	// panel.addTextField(null, recipeTableControl, TableContentControl.FORMAT, //
	// f -> f.fill().withTooltip(TableContentControl.TABLE_CONTROL_TOOLTIP)
	// .modifyEditor(tf -> tf.setIcon(ObservableSwingUtils.getFixedIcon(ObservableSwingUtils.class, "/icons/search.png", 16, 16))
	// .setEmptyText("Search...")))//
	// .addTable(theRecipes, table -> table//
	// .withColumn("Name", String.class, Ingredient::getName, column -> {
	// column.withValueTooltip((ing, name) -> ing.getDescription())//
	// .withMutation(mut -> mut.asText(Format.TEXT)); // TODO Enforce name uniqueness
	// })//
	// .withColumn("Batch Amount", BaqeryUtils.AMOUNT_TYPE, Recipe::getStandardBatch, column -> {
	// column.formatText(amt -> BaqeryUtils.AMOUNT_FORMAT.format(amt));
	// })//
	// .withColumn("Batch Cost", TypeTokens.get().DOUBLE, Recipe::getBatchCost, column -> {
	// column.formatText(cost -> "$" + COST_NUMBER_FORMAT.format(cost));
	// })//
	// .withColumn("Labor Time", TypeTokens.get().of(Duration.class), Recipe::getLaborTime, column -> {
	// column.formatText(labor -> QommonsUtils.printDuration(labor, true));
	// })//
	// .withSelection(theSelectedRecipe, false)//
	// ).addVPanel(this::populateRecipeEditor)//
	// ;
	// }
	//
	// private void populateRecipeEditor(PanelPopulator<?, ?> panel) {
	// panel.fill().visibleWhen(theSelectedRecipe.map(r -> r != null))//
	// .addTextField("Name:", theSelectedRecipe.map(TypeTokens.get().STRING, Ingredient::getName, Recipe::setName, null), Format.TEXT,
	// f -> f.fill())//
	// .addTextField("Description:",
	// theSelectedRecipe.map(TypeTokens.get().STRING, Ingredient::getDescription, Recipe::setDescription, null), Format.TEXT,
	// f -> f.fill())// TODO Make this a text area
	// // TODO Ingredients
	// .addLabel("Ingredient Cost:", theSelectedRecipe.map(Recipe::getIngredientCost), COST_FORMAT, null)//
	// // TODO Hours
	// .addLabel("Labor Cost:", theSelectedRecipe.map(Recipe::getLaborCost), COST_FORMAT, null)//
	// .addTextField("Procedure:", theSelectedRecipe.map(TypeTokens.get().STRING, Recipe::getProcedure, Recipe::setProcedure, null),
	// Format.TEXT, f -> f.fill())// TODO Make this a text area
	// .addTextField("Notes:", theSelectedRecipe.map(TypeTokens.get().STRING, Ingredient::getNotes, Recipe::setNotes, null),
	// Format.TEXT, f -> f.fill())// TODO Make this a text area
	// ;
	// }

	private String getUses(Ingredient ingredient) {
		return ""; // TODO
	}

	/*
			Features:
			Labels
			Costs, Weights, & Measures
			Batch Coding

			Tabs:
			Tab: Ingredients
				Table: Ingredients (Searchable)
					Text: Name
					Label: Description
					Label: Notes
					Label: Used In (Recipes)
				Ingredient Editor
					Text: Name
					Text Area: Description
					Text Area: Notes
					(For Recipes Only)
					Table: Ingredients
						Combo: Ingredient
						Text: Amount (w/unit)
					Text: Standard Batch
					List: Labor (Type:Hours)
					Text Area: Procedure
			Tab: Inventory
				Check: Show Inactive
				Table: Inventory Items (Searchable)
					Text: Name
					Label: Ingredient
					Label: Notes
					Label: Allergens
					Label: Made On
					Text: Batch Code
				InventoryItem Editor
					Text: Name
					Label: Ingredient
					Check: Active
					Text Area: Notes
					List: Allergens
					(For Batches Only)
					Text: Made On
					Text: Batch Code (auto-generated but overrideable)
					Table: Ingredients
						Label: Ingredient
						Combo: InventoryItem
						Text: Batch Codes (editable for basic inventory items)
					Text: Batch Cost (post toggle button: Calculated)
					Button: Print Label (pops up print window)
			Tab: Orders
				Check: Show Inactive
				Table: Orders (Searchable)
				Order Editor
					Text: Name
					Text: Notes
					Table: Items
						Combo: Recipe
						Combo: Batch
						Text: Amount
					Label: Cost (breakdown by labor type/ingredient)
					Text: Price
					Label: Hourly Rate (breakdown by labor type)
	 */
	public static void main(String[] args) {
		ObservableSwingUtils.startApplication("baqery", "./Baqery.xml", Baqery::new);
	}
}
