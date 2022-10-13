package org.quark.finance.ui;

import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.quark.finance.entities.AssetGroup;

public class AssetGroupEditor extends PlanComponentEditor<AssetGroup> {
	public AssetGroupEditor(ObservableValue<AssetGroup> selectedGroup) {
		super(selectedGroup, false, panel -> {});
		/* TODO
		 * List of member funds (links)
		 * List of member processes (links)
		 */
	}

	@Override
	protected String testName(String newName, AssetGroup currentValue) {
		if (newName.indexOf(',') >= 0) {
			return "Group names must not contain commas";
		}
		return null;
	}

	@Override
	protected ObservableValue<String> isShowEnabled() {
		return SettableValue.ALWAYS_ENABLED;
	}
}
