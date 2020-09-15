package org.quark.chores.ui;

import javax.swing.JPanel;

import org.observe.util.swing.PanelPopulation.PanelPopulator;

public class JobAdminPanel extends JPanel {
	private final ChoresUI theUI;

	public JobAdminPanel(ChoresUI ui) {
		theUI = ui;
	}

	public void addPanel(PanelPopulator<?, ?> panel) {
	}
}
