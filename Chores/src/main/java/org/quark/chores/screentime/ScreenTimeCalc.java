package org.quark.chores.screentime;

import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.KeyEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.swing.JOptionPane;
import javax.swing.JTable;

import org.observe.Observable;
import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.collect.ObservableCollection;
import org.observe.collect.ObservableSortedCollection;
import org.observe.util.swing.CategoryRenderStrategy;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.ModelCell;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.WindowPopulation;
import org.qommons.config.StrictXmlReader;
import org.qommons.io.Format;
import org.qommons.io.TextParseException;
import org.qommons.io.XmlSerialWriter;

public class ScreenTimeCalc {
	public static void main(String... args) {
		EventQueue.invokeLater(ScreenTimeCalc::run);
	}

	static void run() {
		ObservableSortedCollection<ScreenTimeEvent> events = ObservableSortedCollection.create(ScreenTimeEvent::compareTo);
		ObservableCollection<ScreenTimeEvent> publishedEvents = events.flow()//
			.refreshEach(evt -> evt.players.simpleChanges())//
			.collect();
		SettableValue<ScreenTimeEvent> selectedEvent = SettableValue.create();
		ObservableCollection<ScreenTimePlayerEvent> playerEvents = ObservableCollection
			.flattenValue(selectedEvent.map(e -> e == null ? null : e.players));
		Observable<?> onPEChange = ObservableValue
			.flattenObservableValue(selectedEvent.map(evt -> evt == null ? null : evt.players.simpleChanges()));
		onPEChange.act(__ -> {
			ScreenTimeEvent event = selectedEvent.get();
			if (event == null || event.isAmountSet)
				return;
			event.amount = event.players.stream().mapToInt(p -> p.amount).sum();
		});
		SettableValue<String> byDayReport = SettableValue.create();
		SettableValue<String> byPlayerReport = SettableValue.create();
		SettableValue<File> exportFile = SettableValue.create();
		SettableValue<File> importFile = SettableValue.create();
		JTable[] playerTable = new JTable[1];
		exportFile.noInitChanges().act(evt -> {
			if (evt.getNewValue() != null)
				exportToFile(events, evt.getNewValue(), playerTable[0]);
			EventQueue.invokeLater(() -> exportFile.set(null));
		});
		importFile.noInitChanges().act(evt -> {
			if (evt.getNewValue() != null)
				importFromFile(events, evt.getNewValue(), playerTable[0]);
			EventQueue.invokeLater(() -> importFile.set(null));
		});
		Observable.or(events.simpleChanges(), onPEChange).act(__ -> regenReports(events, byDayReport, byPlayerReport));
		WindowPopulation.populateWindow(null, null, false, true)//
			.withTitle("Screen Time Accounting")//
			.withVContent(p -> p//
				.addTabs(tabs -> tabs//
					.withHTab("entry", new JustifiedBoxLayout(false).mainJustified().crossJustified(), tab -> tab//
						.addTable(publishedEvents, table -> table.fill().fillV()//
							.withSelection(selectedEvent, false)//
							.withColumn("Day", int.class, e -> e.day,
								col -> col.withWidths(40, 40, 200)
									.withMutation(mut -> mut.mutateAttribute((e, v) -> e.day = v).asText(Format.INT).withRowUpdate(true)))//
							.withColumn("Game", String.class, e -> e.game,
								col -> col.withWidths(40, 40, 200)//
									.withMutation(mut -> mut.mutateAttribute((e, v) -> e.game = v).asText(Format.TEXT).withRowUpdate(true)))//
							.withColumn("Amount", int.class, e -> e.amount,
								col -> col.withWidths(40, 50, 200).withMutation(
									mut -> mut.mutateAttribute((e, v) -> {
										e.amount = v;
										e.isAmountSet = true;
									}).asText(Format.INT).withRowUpdate(true)))//
							.withColumn("Players", String.class, e -> e.getPlayers(),
								col -> col.withWidths(40, 60, 200)
									.withKeyListener(new CategoryRenderStrategy.CategoryKeyAdapter<ScreenTimeEvent, String>() {
									@Override
									public void keyTyped(ModelCell<? extends ScreenTimeEvent, ? extends String> cell, KeyEvent e) {
										EventQueue.invokeLater(playerTable[0]::grabFocus);
									}
								}))//
							.withColumn("Flag", boolean.class, e -> e.flagged, col -> col.withWidths(10, 25, 40)
								.withMutation(mut -> mut.mutateAttribute((evt, f) -> evt.flagged = f).asCheck()).decorate((cell, deco) -> {
									if (cell.getCellValue())
										deco.withBackground(Color.red);
								}))//
							.withAdd(() -> new ScreenTimeEvent(events.peekLast()),
								a -> a.modifyButton(btn -> btn.withIcon(null, "/icons/add.png", 16, 16)))//
							.withRemove(events::removeAll, a -> a.confirm("Delete Event?", "Delete selected event(s)?", true)
								.modifyButton(btn -> btn.withIcon(ObservableSwingUtils.class, "/icons/remove.png", 16, 16)))//
							.withTableOption(opts -> opts.addFileField(null, exportFile, true, btn -> btn.withTooltip("Export")))//
							.withTableOption(opts -> opts.addFileField(null, importFile, true, btn -> btn.withTooltip("Import")))//
						)//
						.addHPanel(null, new JustifiedBoxLayout(true).mainJustified().crossJustified(), p2 -> p2//
							.addTable(playerEvents, //
								table -> {
									table.fill().fillV()//
									.withColumn("Player", String.class, e -> e.player,
										col -> col.withMutation(
											mut -> mut.mutateAttribute((e, v) -> e.player = v).asText(Format.TEXT).withRowUpdate(true)))//
									.withColumn("Amount", int.class, e -> e.amount,
										col -> col.withMutation(
											mut -> mut.mutateAttribute((e, v) -> e.amount = v).asText(Format.INT).withRowUpdate(true)))//
									.withColumn("Payment", String.class, e -> e.type,
										col -> col.withMutation(
											mut -> mut.mutateAttribute((e, v) -> e.type = v).asText(Format.TEXT).withRowUpdate(true)))//
									.withAdd(() -> new ScreenTimePlayerEvent(playerEvents.peekLast()),
										a -> a.modifyButton(btn -> btn.withIcon(ObservableSwingUtils.class, "/icons/add.png", 16, 16)))//
									.withRemove(playerEvents::removeAll, a -> a.confirm("Delete Event?", "Delete selected event(s)?", true)
											.modifyButton(btn -> btn.withIcon(ObservableSwingUtils.class, "/icons/remove.png", 16, 16)));
									playerTable[0] = table.getEditor();
								})), //
						tab -> tab.setName("Entry"))//
					.withVTab("byDay", tab -> tab.addTextArea(null, byDayReport, Format.TEXT, ta -> ta.fill().fillV()),
						tab -> tab.setName("By Day"))//
					.withVTab("byPlayer", tab -> tab.addTextArea(null, byPlayerReport, Format.TEXT, ta -> ta.fill().fillV()),
						tab -> tab.setName("By Player"))//
				)//
			).run(null);
	}

	static class TimePerPlayer {
		int total;
		Map<String, Integer> perGame = new LinkedHashMap<>();
		Map<String, Integer> perType = new TreeMap<>();

		public void addGame(String game, int amount) {
			total += amount;
			perGame.compute(game, (__, old) -> old == null ? amount : old + amount);
		}

		public void addType(String type, int amount) {
			perType.compute(type, (__, old) -> old == null ? amount : old + amount);
		}

		public StringBuilder print(StringBuilder str) {
			str.append(total).append(" total (");
			for (Map.Entry<String, Integer> byGame : perGame.entrySet()) {
				str.append(byGame.getKey()).append(": ").append(byGame.getValue()).append(" ");
			}
			str.append("| ");
			for (Map.Entry<String, Integer> byType : perType.entrySet()) {
				str.append(byType.getKey()).append(": ").append(byType.getValue()).append(" ");
			}
			str.append(')');
			return str;
		}
	}

	private static void regenReports(ObservableSortedCollection<ScreenTimeEvent> events, SettableValue<String> byDayReport,
		SettableValue<String> byPlayerReport) {
		int day = -1;
		int total = 0;
		Map<String, Integer> timePerGame = new LinkedHashMap<>();
		Map<String, TimePerPlayer> timePerPlayer = new LinkedHashMap<>();
		Map<String, TimePerPlayer> totalTimePerPlayer = new LinkedHashMap<>();
		Set<String> dayPlayers = new LinkedHashSet<>();

		StringBuilder byDay = new StringBuilder();

		for (ScreenTimeEvent event : events) {
			if (day != event.day) {
				printAndClearByDay(byDay, day, total, timePerGame, timePerPlayer);
				total = 0;
			}
			day = event.day;
			total += event.amount;
			timePerGame.compute(event.game, (__, old) -> old == null ? event.amount : old + event.amount);
			for (ScreenTimePlayerEvent player : event.players) {
				dayPlayers.add(player.player);
				timePerPlayer.computeIfAbsent(player.player, __ -> new TimePerPlayer()).addType(player.type, player.amount);
				totalTimePerPlayer.computeIfAbsent(player.player, __ -> new TimePerPlayer()).addType(player.type, player.amount);
			}
			for (String player : dayPlayers) {
				timePerPlayer.get(player).addGame(event.game, event.amount);
				totalTimePerPlayer.get(player).addGame(event.game, event.amount);
			}
			dayPlayers.clear();
		}
		printAndClearByDay(byDay, day, total, timePerGame, timePerPlayer);
		byDayReport.set(byDay.toString());

		StringBuilder byPlayerStr = new StringBuilder();
		for (Map.Entry<String, TimePerPlayer> byPlayer : totalTimePerPlayer.entrySet()) {
			byPlayer.getValue().print(byPlayerStr.append(byPlayer.getKey()).append(": ")).append('\n');
		}
		byPlayerReport.set(byPlayerStr.toString());
	}

	private static void printAndClearByDay(StringBuilder byDay, int day, int total, Map<String, Integer> timePerGame,
		Map<String, TimePerPlayer> timePerPlayer) {
		if (total == 0)
			return;
		byDay.append("Day ").append(day).append(" Total: ").append(total).append("\n\tBy Game:\n");
		for (Map.Entry<String, Integer> byGame : timePerGame.entrySet()) {
			byDay.append("\t").append(byGame.getKey()).append(": ").append(byGame.getValue()).append('\n');
		}
		byDay.append("\tBy Player:\n");
		for (Map.Entry<String, TimePerPlayer> byPlayer : timePerPlayer.entrySet()) {
			byPlayer.getValue().print(byDay.append("\t").append(byPlayer.getKey()).append(": ")).append('\n');
		}
		byDay.append("\n");
		timePerGame.clear();
		timePerPlayer.clear();
	}

	private static void exportToFile(List<ScreenTimeEvent> events, File file, Component component) {
		try (Writer w = new BufferedWriter(new FileWriter(file))) {
			XmlSerialWriter.createDocument(w).writeRoot("screen-time", xml -> {
				for (ScreenTimeEvent event : events) {
					xml.addChild("event", evt -> {
						evt.addAttribute("day", "" + event.day).addAttribute("game", event.game).addAttribute("amount", "" + event.amount)
							.addAttribute("amount-set", "" + event.isAmountSet).addAttribute("flagged", "" + event.flagged);
						for (ScreenTimePlayerEvent player : event.players) {
							evt.addChild("player", plyr -> {
								plyr.addAttribute("player", player.player).addAttribute("amount", "" + player.amount).addAttribute("type",
									player.type);
							});
						}
					});
				}
			});
		} catch (IOException | RuntimeException e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(component, e.getMessage(), "Failed to export", JOptionPane.ERROR_MESSAGE);
		}
	}

	private static void importFromFile(List<ScreenTimeEvent> events, File file, Component component) {
		StrictXmlReader xml;
		try {
			xml = StrictXmlReader.read(() -> new FileInputStream(file));
			if (!xml.getName().equals("screen-time"))
				throw new IllegalArgumentException("Not a screen time export");
			for (StrictXmlReader event : xml.getElements("event")) {
				ScreenTimeEvent newEvent = new ScreenTimeEvent(null);
				newEvent.players.clear();
				newEvent.day = Integer.parseInt(event.getAttribute("day"));
				newEvent.game = event.getAttribute("game");
				newEvent.amount = Integer.parseInt(event.getAttribute("amount"));
				newEvent.flagged = "true".equalsIgnoreCase(event.getAttributeIfExists("flagged"));
				for (StrictXmlReader player : event.getElements("player")) {
					ScreenTimePlayerEvent plyr = new ScreenTimePlayerEvent(null);
					plyr.player = player.getAttribute("player");
					plyr.amount = Integer.parseInt(player.getAttribute("amount"));
					plyr.type = player.getAttribute("type");
					newEvent.players.add(plyr);
				}
				events.add(newEvent);
			}
		} catch (IOException | TextParseException | RuntimeException e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(component, e.getMessage(), "Failed to export", JOptionPane.ERROR_MESSAGE);
		}
	}
}
