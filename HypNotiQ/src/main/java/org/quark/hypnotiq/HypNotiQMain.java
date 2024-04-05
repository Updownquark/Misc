package org.quark.hypnotiq;

import java.awt.*;
import java.awt.TrayIcon.MessageType;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.CellEditor;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.observe.SettableValue;
import org.observe.SimpleObservable;
import org.observe.assoc.ObservableMap;
import org.observe.assoc.ObservableMultiMap;
import org.observe.collect.CollectionChangeType;
import org.observe.collect.ObservableCollection;
import org.observe.collect.ObservableSortedCollection;
import org.observe.config.*;
import org.observe.config.ObservableConfigFormat.EntityConfigFormat;
import org.observe.ext.util.GitHubApiHelper;
import org.observe.ext.util.GitHubApiHelper.Release;
import org.observe.util.EntityReflector;
import org.observe.util.TypeTokens;
import org.observe.util.swing.*;
import org.observe.util.swing.AppPopulation.ObservableUiBuilder;
import org.observe.util.swing.PanelPopulation.PanelPopulator;
import org.qommons.*;
import org.qommons.QommonsUtils.TimePrecision;
import org.qommons.TimeUtils.DurationComponentType;
import org.qommons.TimeUtils.ParsedDuration;
import org.qommons.TimeUtils.RecurrenceInterval;
import org.qommons.collect.CollectionElement;
import org.qommons.collect.CollectionUtils;
import org.qommons.collect.CollectionUtils.ElementSyncAction;
import org.qommons.collect.CollectionUtils.ElementSyncInput;
import org.qommons.collect.ElementId;
import org.qommons.io.Format;
import org.qommons.io.SpinnerFormat;
import org.qommons.threading.QommonsTimer;
import org.quark.hypnotiq.entities.Event;
import org.quark.hypnotiq.entities.Note;
import org.quark.hypnotiq.entities.Notification;
import org.quark.hypnotiq.entities.Subject;

/** A note-taking app that facilitates very flexible, very persistent notifications */
public class HypNotiQMain extends JPanel {
	private static final SpinnerFormat<Instant> FUTURE_DATE_FORMAT = SpinnerFormat.flexDate(Instant::now, "EEE MMM dd, yyyy", opts -> opts
			.withMaxResolution(TimeUtils.DateElementType.Second).withEvaluationType(TimeUtils.RelativeInstantEvaluation.Future));
	private static final SpinnerFormat<Instant> PAST_DATE_FORMAT = SpinnerFormat.flexDate(Instant::now, "EEE MMM dd, yyyy",
			opts -> opts.withMaxResolution(TimeUtils.DateElementType.Second).withEvaluationType(TimeUtils.RelativeInstantEvaluation.Past));
	private static final Pattern SUBJECT_PATTERN = Pattern.compile("\\#"//
			+ "[a-zA-Z_\\./0-9]{2,}");
	private static final Pattern ALL_NUMBERS = Pattern.compile("\\d*");
	private static final Duration MAX_SLEEP = Duration.ofSeconds(10);

	private final ObservableConfig theConfig;
	private final ObservableConfigParseSession theSession;
	private final SyncValueSet<Subject> theSubjects;
	private final SyncValueSet<Note> theNotes;
	private final ObservableCollection<ActiveEvent> theEvents;
	private final ObservableCollection<ActiveNotification> theNotifications;
	// private final ObservableSortedCollection<ActiveEvent> theActiveEvents;
	private final ObservableSortedCollection<ActiveNotification> theActiveNotifications;
	private final ObservableMultiMap<Long, ActiveEvent> theEventsById;
	private final ObservableMap<String, Subject> theSubjectByName;
	private final QommonsTimer.TaskHandle theAlertTask;

	private final TrayIcon theTrayIcon;
	private final PopupMenu thePopup;
	private final MenuItem theSnoozeAllItem;
	private boolean hasSnoozeAll;
	private Duration theReNotifyDuration = Duration.ofMinutes(1);
	private boolean theEventCallbackLock;

	private final ObservableCollection<Subject> theEditingSubjects;
	private final ObservableCollection<Note> theEditingNotes;
	private final List<PanelPopulation.TabEditor<?>> theEditingSubjectTabs;
	private final List<PanelPopulation.TabEditor<?>> theEditingNoteTabs;

	private final SettableValue<Subject> theSelectedSubject = SettableValue.create();
	private final SettableValue<Note> theSelectedNote = SettableValue.create();
	private final SettableValue<ActiveEvent> theSelectedEvent = SettableValue.create();
	private final SimpleObservable<Void> theSubjectSelection = SimpleObservable.build().build();
	private final SimpleObservable<Void> theNoteSelection = SimpleObservable.build().build();
	private final SimpleObservable<Void> theEventsSelection = SimpleObservable.build().build();

	private PanelPopulation.TabPaneEditor<?, ?> theSubjectTabs;
	private PanelPopulation.TabPaneEditor<?, ?> theNoteTabs;

	/**
	 * @param config The config to store UI settings and the source of the entities
	 * @param session The parse session used for entity parsing
	 * @param subjects The configured {@link Subject} set
	 * @param notes The configured {@link Note} set
	 */
	public HypNotiQMain(ObservableConfig config, ObservableConfigParseSession session, SyncValueSet<Subject> subjects,
			SyncValueSet<Note> notes) {
		theConfig = config;
		theSession = session;
		theSubjects = subjects;
		theNotes = notes;
		theSubjectByName = theSubjects.getValues().reverse().flow().groupBy(s -> s.getName().toLowerCase(), (__, s) -> s).gather()
				.singleMap(true);
		theEvents = ObservableCollection.create();
		theNotifications = theEvents.flow()//
				.flatMap(LambdaUtils.printableFn(event -> event.getNotifications().flow(), "notifications", null))//
				.collect();
		// theActiveEvents = theEvents.flow().filter(n -> n.getNextOccurrence() == null ? "Not Active" :
		// null).sorted(ActiveEvent::compareTo)
		// .collect();
		theActiveNotifications = theNotifications.flow().filter(n -> n.getNextNotification() == null ? "Not Active" : null)
				.sorted(ActiveNotification::compareTo).collect();
		theEventsById = theEvents.flow().groupBy(n -> n.getEvent().getNote().getId(), (id, n) -> n).gather();
		theEditingSubjects = ObservableCollection.create();
		theEditingNotes = ObservableCollection.create();
		theEditingSubjectTabs = new ArrayList<>();
		theEditingNoteTabs = new ArrayList<>();

		theNotifications.subscribe(evt -> {
			if (evt.getType() == CollectionChangeType.add) {
				evt.getNewValue().theElement = evt.getElementId();
			}
		}, true);
		for (Note note : theNotes.getValues()) {
			for (Event event : note.getEvents().getValues()) {
				ActiveEvent an = new ActiveEvent(event);
				an.theElement = theEvents.addElement(an, false).getElementId();
			}
		}
		theAlertTask = QommonsTimer.getCommonInstance().build(this::processNotifications, null, false).onEDT();
		// Watch for entity changes
		theConfig.watch(ObservableConfigPath.buildPath(ObservableConfigPath.ANY_NAME).multi(true).build()).act(evt -> {
			if (theEventCallbackLock) {
				return;
			}
			theEventCallbackLock = true;
			try {
				boolean terminal = true;
				for (ObservableConfig target : evt.relativePath.reverse()) {
					if (target.getName().equals("event")) {
						Event event = (Event) target.getParsedItem(theSession);
						if (event == null || EntityConfigFormat.getConfig(event) != target) {
							return;
						}
						ActiveEvent found = null;
						long id = event.getNote().getId();
						for (ActiveEvent an : theEventsById.get(id)) {
							if (an.getEvent() == event) {
								found = an;
								break;
							}
						}
						if (found != null) {
							if (terminal && evt.changeType == CollectionChangeType.remove) {
								theEvents.mutableElement(found.theElement).remove();
							} else {
								found.update();
								theEvents.mutableElement(found.theElement).set(found); // Update
								try (Transaction t = theNotifications.lock(true, evt)) {
									for (ActiveNotification not : found.getNotifications()) {
										theNotifications.mutableElement(not.theElement).set(not);
									}
								}
							}
						} else if (evt.changeType != CollectionChangeType.remove) {
							found = new ActiveEvent(event);
							found.theElement = theEvents.addElement(found, false).getElementId();
						}
						break;
					} else if (terminal && evt.changeType == CollectionChangeType.remove && target.getName().equals("note")) {
						Note note = (Note) target.getParsedItem(theSession);
						if (note == null || EntityConfigFormat.getConfig(note) != target) {
							continue;
						}
						for (Event event : note.getEvents().getValues()) {
							for (ActiveEvent an : theEvents) {
								if (an.getEvent() == event) {
									theEvents.mutableElement(an.theElement).remove();
								}
							}
						}
						for (Subject ref : note.getReferences()) {
							ref.getReferences().remove(note);
						}
						break;
					}
					terminal = false;
				}
			} finally {
				theEventCallbackLock = false;
			}
		});
		theActiveNotifications.simpleChanges().act(__ -> {
			if (!theEventCallbackLock) {
				EventQueue.invokeLater(() -> {
					processNotifications();
				});
			}
		});

		if (!SystemTray.isSupported()) {
			JOptionPane.showMessageDialog(this, "System tray is not supported", "HypNotiQ requires the system tray",
					JOptionPane.ERROR_MESSAGE);
			throw new IllegalStateException("HypNotiQ requires the system tray");
		}
		thePopup = new PopupMenu();
		MenuItem exitItem = new MenuItem("Exit");
		thePopup.add(exitItem);
		theTrayIcon = new TrayIcon(ObservableSwingUtils.getIcon(HypNotiQMain.class, "/icons/icons8-reminder-48.png").getImage(), "HypNotiQ",
				thePopup);
		exitItem.addActionListener(evt -> {
			EventQueue.invokeLater(() -> {
				SystemTray.getSystemTray().remove(theTrayIcon);
				System.exit(0);
			});
		});
		theTrayIcon.setImageAutoSize(true);
		theTrayIcon.setToolTip("Open HypNotiQ");
		theTrayIcon.setPopupMenu(thePopup);
		theTrayIcon.addActionListener(evt -> {
			Window w = SwingUtilities.getWindowAncestor(HypNotiQMain.this);
			w.setVisible(true);
			w.toFront();
			w.requestFocus();
			theEventsSelection.onNext(null);
		});
		theTrayIcon.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent evt) {
				if (SwingUtilities.isLeftMouseButton(evt)) {
					Window w = SwingUtilities.getWindowAncestor(HypNotiQMain.this);
					w.setVisible(true);
					w.toFront();
					w.requestFocus();
				}
			}
		});
		try {
			SystemTray.getSystemTray().add(theTrayIcon);
		} catch (AWTException e) {
			System.err.println("Could not install tray icon");
			e.printStackTrace();
		}
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(theTrayIcon));
		}, "HypNotiQ Shutdown"));

		boolean[] subjectAdjustmentReentrant = new boolean[1];
		theNotes.getValues().onChange(evt -> EventQueue.invokeLater(() -> {
			if (subjectAdjustmentReentrant[0] || evt.getType() != CollectionChangeType.set) {
				return;
			}
			subjectAdjustmentReentrant[0] = true;
			try (Transaction t = theConfig.lock(true, null)) { // Can't use evt as a cause because that's from a different thread
				String content = evt.getNewValue().getContent();
				Set<String> refNames = new LinkedHashSet<>();
				if (content != null) {
					Matcher subjectMatch = SUBJECT_PATTERN.matcher(content);
					while (subjectMatch.find()) {
						String subjectName = subjectMatch.group().substring(1).toLowerCase();
						if (subjectName.charAt(0) == '/') {
							continue;
						} else if (ALL_NUMBERS.matcher(subjectName).matches()) {
							continue;
						}
						refNames.add(subjectName);
					}
				}
				List<Subject> oldRefs = evt.getNewValue().getReferences();
				boolean[] modified = new boolean[1];
				CollectionUtils
				.<Subject, String> synchronize(oldRefs, new ArrayList<>(refNames),
						(sub, name) -> sub != null && sub.getName().equals(name))//
				.simple(s -> {
					Subject sub = theSubjectByName.get(s);
					if (sub == null) {
						sub = theSubjects.create()//
								.with(Subject::getName, s)//
								.create().get();
					}
					sub.getReferences().add(evt.getNewValue());
					return sub;
				}).rightOrder().commonUsesLeft().onLeft(left -> {
					modified[0] = true;
					left.getLeftValue()//
					.getReferences()//
					.remove(//
							evt.getNewValue());
					if (left.getLeftValue().getReferences().isEmpty()) {
						theSubjects.getValues().remove(left.getLeftValue());
					}
				}).onRight(right -> {
					modified[0] = true;
				}).adjust();
			} finally {
				subjectAdjustmentReentrant[0] = false;
			}
		}));
		theSnoozeAllItem = new MenuItem("Snooze All 5 min");
		theSnoozeAllItem.addActionListener(evt -> {
			theEventCallbackLock = true;
			try {
				Instant now = Instant.now();
				for (ActiveNotification not : theActiveNotifications) {
					if (not.getNextNotification() == null || not.getNextNotification().compareTo(now) >= 0) {
						break;
					}
					not.getNotification().setSnoozeCount(not.getNotification().getSnoozeCount() + 1);
					not.getNotification().setSnoozeTime(now.plus(Duration.ofMinutes(5)));
					not.refreshNext();
				}
			} finally {
				theEventCallbackLock = false;
			}
			processNotifications();
		});

		initComponents();

		processNotifications();
	}

	/** @return All stored notes */
	public SyncValueSet<Note> getNotes() {
		return theNotes;
	}

	/** @return All stored subjects */
	public SyncValueSet<Subject> getSubjects() {
		return theSubjects;
	}

	/** @return All notifications for all notes, with an associated next alert time (if any) */
	public ObservableCollection<ActiveEvent> getEvents() {
		return theEvents;
	}

	/** @return The config for UI settings */
	public ObservableConfig getConfig() {
		return theConfig;
	}

	private Set<ActiveNotification> theCurrentNotifications = new HashSet<>();
	private Instant theLastEvent;
	private Instant theNextEvent;

	private void processNotifications() {
		if (theEventCallbackLock) {
			return;
		}
		theEventCallbackLock = true;
		try {
			Instant now = Instant.now();
			Instant nextAlert = null;
			Iterator<ActiveNotification> cnIter = theCurrentNotifications.iterator();
			while (cnIter.hasNext()) {
				ActiveNotification cn = cnIter.next();
				if (!cn.theElement.isPresent()) {
					cnIter.remove();
				} else if (cn.getNextNotification() == null || cn.getNextNotification().compareTo(now) > 0) {
					theNotifications.mutableElement(cn.theElement).set(cn);
					cnIter.remove();
				}
			}
			boolean reAlert = !theCurrentNotifications.isEmpty() //
					&& (theLastEvent == null || now.compareTo(theLastEvent.plus(theReNotifyDuration)) > 0);
			Instant reNotify;
			if (theLastEvent == null) {
				reNotify = now.plus(theReNotifyDuration);
			} else {
				reNotify = theLastEvent.plus(theReNotifyDuration);
				if (reNotify.compareTo(now) < 0) {
					reNotify = now.plus(theReNotifyDuration);
				}
			}
			List<ActiveNotification> currentNotifications = new LinkedList<>();
			for (CollectionElement<ActiveNotification> event : theActiveNotifications.elements()) {
				if (event.get().getNextNotification().compareTo(now) > 0) {
					nextAlert = event.get().getNextNotification();
					break;
				}
				currentNotifications.add(event.get());
				if (theCurrentNotifications.add(event.get())) {
					reAlert = true;
					theNotifications.mutableElement(event.get().theElement).set(event.get());
				}
			}

			if (currentNotifications.isEmpty()) {//
				if (nextAlert == null) {
					theTrayIcon.setToolTip("HypNotiQ: No active reminders");
				} else {
					theTrayIcon.setToolTip("HypNotiQ: " + theActiveNotifications.size() + " active reminder"
							+ (theActiveNotifications.size() == 1 ? "" : "s") + "\nNext at "
							+ QommonsUtils.printRelativeTime(nextAlert.toEpochMilli(), System.currentTimeMillis(), TimePrecision.SECONDS,
									TimeZone.getDefault(), 0, null));
				}
			} else if (currentNotifications.size() == 1) {
				ActiveNotification notification = currentNotifications.iterator().next();
				theTrayIcon.setToolTip("HypNotiQ: 1 current reminder");
				if (reAlert) {
					String msg = notification.getEvent().getEvent().getName();
					if (notification.getNotification().getSnoozeCount() > 0) {
						msg += " (Snoozed ";
						switch (notification.getNotification().getSnoozeCount()) {
						case 1:
							msg += "Once";
							break;
						case 2:
							msg += "Twice";
							break;
						default:
							msg += notification.getNotification().getSnoozeCount() + " Times";
							break;
						}
						msg += ")";
					}
					theLastEvent = now;
					theTrayIcon.displayMessage(notification.getEvent().getEvent().getName(), msg, MessageType.INFO);
				}
			} else {
				theTrayIcon.setToolTip("HypNotiQ: " + currentNotifications.size() + " current reminders");
				if (reAlert) {
					StringBuilder msg = new StringBuilder();
					int i = 0;
					for (ActiveNotification not : currentNotifications) {
						if (i > 0) {
							msg.append('\n');
						}
						i++;
						if (i < currentNotifications.size() && i == 3) {
							msg.append("\nAnd ").append(currentNotifications.size() - i).append(" other notifications");
						} else {
							msg.append(not.getEvent().getEvent().getName());
						}
					}
					theLastEvent = now;
					theTrayIcon.displayMessage(currentNotifications.size() + " Events", msg.toString(), MessageType.INFO);
				}
			}

			if (!currentNotifications.isEmpty()) {
				if (nextAlert == null || nextAlert.compareTo(reNotify) > 0) {
					nextAlert = reNotify;
				}
				if (!hasSnoozeAll) {
					thePopup.add(theSnoozeAllItem);
					hasSnoozeAll = true;
				}
			} else if (hasSnoozeAll) {
				thePopup.remove(theSnoozeAllItem);
				hasSnoozeAll = false;
			}

			if (nextAlert != null) {
				if (!Objects.equals(theNextEvent, nextAlert)) {
					// SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS");
					// System.out.println("Next alert at " + format.format(Date.from(nextAlert)) + " ("
					// + QommonsUtils.printTimeLength(nextAlert.toEpochMilli() - System.currentTimeMillis()) + ")");
					theNextEvent = nextAlert;
				}
				if (TimeUtils.between(now, nextAlert).compareTo(MAX_SLEEP) > 0) {
					theAlertTask.runNextAt(nextAlert);
				} else {
					theAlertTask.runNextIn(MAX_SLEEP);
				}
			} else {
				// System.out.println("No next alert");
				theAlertTask.setActive(false);
			}
		} finally {
			theEventCallbackLock = false;
		}
	}

	private void initComponents() {
		PanelPopulation.populateVPanel(this, null).addTabs(tabs -> {
			tabs.fill().fillV()//
			.withVTab("subjects", subjectsPanel -> subjectsPanel.addTabs(subjectTabs -> {
				theSubjectTabs = subjectTabs;
				subjectTabs.fill().fillV().withVTab("Main", subjectsPanel2 -> populateSubjectsTab(subjectsPanel2.fill().fillV()),
						subjectTab2 -> subjectTab2.setName("Subjects"));
			}), subjectsTab -> subjectsTab.setName("Subjects").selectOn(theSubjectSelection))//
			.withVTab("notes", notesPanel -> notesPanel.fill().fillV().addTabs(noteTabs -> {
				theNoteTabs = noteTabs;
				noteTabs.fill().fillV().withVTab("Main", notesPanel2 -> populateNotesTab(notesPanel2.fill().fillV()),
						noteTab2 -> noteTab2.setName("Notes"));
			}), notesTab -> notesTab.setName("Notes").selectOn(theNoteSelection))//
			.withVTab("notifications", notificationsPanel -> populateNotificationsTab(notificationsPanel.fill().fillV()),
					notificationsTab -> notificationsTab.setName("Notifications").selectOn(theEventsSelection))//
			;
		})//
		;
		theEditingSubjects.onChange(evt -> {
			switch (evt.getType()) {
			case add:
				theSubjectTabs.withVTab(evt.getNewValue().getId(), subjectPanel -> populateSubjectEditor(subjectPanel, evt.getNewValue()),
						subjectTab -> {
							theEditingSubjectTabs.add(evt.getIndex(), subjectTab);
							subjectTab.setName(SettableValue.create(evt.getNewValue().getName())).setRemovable(true).onRemove(__ -> {
								theEditingSubjects.mutableElement(evt.getElementId()).remove();
							});
						});
				theEditingSubjectTabs.get(evt.getIndex()).select();
				break;
			case remove:
				theEditingSubjectTabs.remove(evt.getIndex()).remove();
				break;
			case set:
				break;
			}
		});
		theEditingNotes.onChange(evt -> {
			switch (evt.getType()) {
			case add:
				theNoteTabs.withVTab(evt.getNewValue().getId(), notePanel -> populateNoteEditor(notePanel, evt.getNewValue()), noteTab -> {
					theEditingNoteTabs.add(evt.getIndex(), noteTab);
					noteTab.setName(SettableValue.create(evt.getNewValue().getName())).setRemovable(true).onRemove(__ -> {
						theEditingNotes.mutableElement(evt.getElementId()).remove();
					});
				});
				theEditingNoteTabs.get(evt.getIndex()).select();
				break;
			case remove:
				theEditingNoteTabs.remove(evt.getIndex()).remove();
				break;
			case set:
				break;
			}
		});
		theSubjectSelection.act(__ -> {
			CollectionElement<Subject> found = theEditingSubjects.getElement(theSelectedSubject.get(), true);
			if (found != null) {
				theEditingSubjectTabs.get(theEditingSubjects.getElementsBefore(found.getElementId())).select();
			} else {
				theEditingSubjects.add(theSelectedSubject.get());
			}
		});
		theNoteSelection.act(__ -> {
			CollectionElement<Note> found = theEditingNotes.getElement(theSelectedNote.get(), true);
			if (found != null) {
				theEditingNoteTabs.get(theEditingNotes.getElementsBefore(found.getElementId())).select();
			} else {
				theEditingNotes.add(theSelectedNote.get());
			}
		});
		theSubjects.getValues().changes().act(evt -> {
			for (Subject subject : evt.getValues()) {
				CollectionElement<Subject> found = theEditingSubjects.getElement(subject, true);
				if (found != null) {
					theEditingSubjectTabs.get(theEditingSubjects.getElementsBefore(found.getElementId())).setName(subject.getName());
				}
			}
		});
		theNotes.getValues().changes().act(evt -> {
			for (Note note : evt.getValues()) {
				CollectionElement<Note> found = theEditingNotes.getElement(note, true);
				if (found != null) {
					theEditingNoteTabs.get(theEditingNotes.getElementsBefore(found.getElementId())).setName(note.getName());
				}
			}
		});
	}

	private void populateSubjectsTab(PanelPopulator<?, ?> subjectsTab) {
		SettableValue<TableContentControl> filter = SettableValue.create(TableContentControl.DEFAULT);
		subjectsTab//
		.addTextField(null, filter, TableContentControl.FORMAT,
				tf -> tf.fill().withTooltip(TableContentControl.TABLE_CONTROL_TOOLTIP)
				.modifyEditor(tf2 -> tf2.setEmptyText("Search...").setCommitOnType(true)))//
		.addTable(theSubjects.getValues().flow().sorted(Named.DISTINCT_NUMBER_TOLERANT).collect(), table -> {
			table.fill().fillV()//
			.withFiltering(filter)//
			.withItemName("Subject")//
			.withNameColumn(Subject::getName, null, true, col -> col.withWidths(50, 120, 300))//
			.withColumn("Last Mentioned", Instant.class, subject -> {
				Instant lm = null;
				for (Note note : subject.getReferences()) {
					if (lm == null || note.getModified().compareTo(lm) > 0) {
						lm = note.getModified();
					}
				}
				return lm;
			}, col -> {
				col.formatText(t -> t == null ? "Never"
						: QommonsUtils.printRelativeTime(t.toEpochMilli(), System.currentTimeMillis(),
								QommonsUtils.TimePrecision.SECONDS, TimeZone.getDefault(), 0, null));
				col.withWidths(100, 150, 300);
			})//
			.withMouseListener(new ObservableTableModel.RowMouseAdapter<Subject>() {
				@Override
				public void mouseClicked(ModelRow<? extends Subject> row, MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
						selectSubject(row.getModelValue());
					}
				}
			})//
			.withSelection(theSelectedSubject, false)//
			;
		});
	}

	private void populateNotesTab(PanelPopulator<?, ?> notesTab) {
		SettableValue<TableContentControl> filter = SettableValue.create(TableContentControl.DEFAULT);
		notesTab//
		.addTextField(null, filter, TableContentControl.FORMAT,
				tf -> tf.fill().withTooltip(TableContentControl.TABLE_CONTROL_TOOLTIP)
				.modifyEditor(tf2 -> tf2.setEmptyText("Search...").setCommitOnType(true)))//
		.addTable(theNotes.getValues(), table -> {
			table.fill().fillV()//
			.withFiltering(filter)//
			.withItemName("Notes")//
			.withNameColumn(Note::getName, null/*(note, name) -> { //Notes not editable here
																								note.setName(name);
																								note.setModified(Instant.now());
																								}*/, false,
																								col -> col.withWidths(50, 120, 300))//
			.withColumn("Content", String.class, Note::getContent, null)//
			.withColumn("References", String.class, //
					note -> StringUtils.print(", ", note.getReferences(), String::valueOf).toString(), null)//
			.withColumn("Modified", Instant.class, Note::getModified,
					col -> col.withWidths(100, 150, 300)
					.formatText(t -> QommonsUtils.printRelativeTime(t.toEpochMilli(), System.currentTimeMillis(),
							QommonsUtils.TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)))//
			.withMouseListener(new ObservableTableModel.RowMouseAdapter<Note>() {
				@Override
				public void mouseClicked(ModelRow<? extends Note> row, MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
						selectNote(row.getModelValue());
					}
				}
			})//
			.withSelection(theSelectedNote, false)//
			.withAdd(() -> {
				Instant now = Instant.now();
				Note note = theNotes.create()//
						.with(Note::getName, "New Note")//
						.with(Note::getContent, "")//
						.with(Note::getModified, now)//
						.create().get();
				selectNote(note);
				return note;
			}, null)//
			.withRemove(notes -> {
				for (Note note : notes) {
					for (Subject ref : note.getReferences()) {
						ref.getReferences().remove(note);
					}
				}

				theNotes.getValues().removeAll(notes);
			}, mod -> mod.confirmForItems("Confirm Note Deletion", "Permanently delete", "?", true))//
			;
		});
	}

	private void populateNotificationsTab(PanelPopulator<?, ?> notificationsTab) {
		SettableValue<TableContentControl> filter = SettableValue.create(TableContentControl.DEFAULT);
		notificationsTab//
		.addTextField(null, filter, TableContentControl.FORMAT,
				tf -> tf.fill().withTooltip(TableContentControl.TABLE_CONTROL_TOOLTIP)
				.modifyEditor(tf2 -> tf2.setEmptyText("Search...").setCommitOnType(true)))//
		.addTable(theActiveNotifications, table -> {
			table.fill().fillV()//
			.withFiltering(filter)//
			.withItemName("Notification")//
			// .withColumn("Active", boolean.class, n -> n.getEvent().isActive(),
			// col -> col.withWidths(10, 40, 50).withMutation(mut -> mut.mutateAttribute((n, a) -> {
			// theEventCallbackLock = true;
			// try {
			// n.getEvent().setActive(a);
			// } finally {
			// theEventCallbackLock = true;
			// }
			// theEvents.mutableElement(n.theElement).set(n);
			// }).asCheck()))//
			.withColumn("Note", String.class, not -> not.getEvent().getEvent().getNote().getName(),
					col -> col.withWidths(100, 200, 500))//
			.withColumn("Event", String.class, not -> not.getEvent().getEvent().getName(),
					col -> col.withWidths(50, 150, 500))//
			.withColumn("Reminder", String.class, not -> not.getNotification().getName(),
					col -> col.withWidths(50, 100, 500))//
			.withColumn("Recurs", String.class, not -> not.getEvent().getEvent().getRecurInterval(), null)//
			.withColumn("Event Time", Instant.class, not -> not.getEventOccurrence(), this::occurrenceColumn)//
			.withColumn("Reminder Time", Instant.class, not -> not.getNextNotification(), this::occurrenceColumn)//
			;
			populateNotificationActions(table, notificationsTab.getContainer(), hasSnoozeAll);
			table.withMouseListener(new ObservableTableModel.RowMouseAdapter<ActiveNotification>() {
				@Override
				public void mouseClicked(ModelRow<? extends ActiveNotification> row, MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
						selectNote(row.getModelValue().getEvent().getEvent().getNote());
					}
				}
			})//
			;
		});
	}

	private void occurrenceColumn(CategoryRenderStrategy<?, Instant> col) {
		col.withWidths(100, 150, 300).decorate((cell, deco) -> {
			Instant next = cell.getCellValue();
			if (next == null) {
				return;
			}
			Instant now = Instant.now();
			boolean notified = false, today = false;
			if (next.compareTo(now) <= 0) {
				notified = true;
			} else {
				long offset = TimeZone.getDefault().getOffset(now.toEpochMilli()) / 1000;
				long day = (next.getEpochSecond() + offset) / (24L * 60 * 60);
				long nowDay = (now.getEpochSecond() + offset) / (24L * 60 * 60);
				today = day == nowDay;
			}
			if (notified) {
				deco.withBorder(BorderFactory.createLineBorder(Color.red));
			} else if (today) {
				deco.withBorder(BorderFactory.createLineBorder(Color.blue));
			}
		}).formatText(t -> {
			if (t == null) {
				return "";
			}
			TimeZone tz = TimeZone.getDefault();
			Calendar cal = TimeUtils.CALENDAR.get();
			cal.setTimeZone(tz);
			cal.setTimeInMillis(System.currentTimeMillis());
			int day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
			cal.setTimeInMillis(t.toEpochMilli());
			StringBuilder str = new StringBuilder();
			if (cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR) != day) {
				str.append(TimeUtils.getWeekDaysAbbrev().get(cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY)).append(' ');
			}
			str.append(QommonsUtils.printRelativeTime(t.toEpochMilli(), System.currentTimeMillis(), QommonsUtils.TimePrecision.MINUTES, tz,
					0, null));
			return str.toString();
		});
	}

	private static void populateEventName(PanelPopulation.TableBuilder<ActiveEvent, ?, ?> table, Container container, boolean mutable) {
		table//
		.withNameColumn(n -> n.getEvent().getName(), mutable ? (not, name) -> {
			not.getEvent().setName(name);
			not.getEvent().getNote().setModified(Instant.now());
		} : null, false, col -> col.withWidths(100, 200, 500));
	}

	private void populateNextAlertTime(PanelPopulation.TableBuilder<ActiveEvent, ?, ?> table, Container container, boolean mutable) {
		table.withColumn("Next Occurs", Instant.class, ActiveEvent::getNextOccurrence, col -> {
			col.withWidths(100, 150, 300);
			if (mutable) {
				col.withMutation(mut -> {
					mut.mutateAttribute((event, time) -> {
						theEventCallbackLock = true;
						try {
							if (!event.getEvent().isActive()) {
								if (table.alert("Set Active", "This event is not active.\nActivate it?").confirm(false)) {
									event.getEvent().setActive(true);
								}
							}
							if (event.getEvent().getEndTime() != null && time.compareTo(event.getEvent().getEndTime()) >= 0) {
								if (table.alert("Clear End Time?",
										"The given next alert time ("
												+ QommonsUtils.printRelativeTime(time.toEpochMilli(), System.currentTimeMillis(),
														QommonsUtils.TimePrecision.MINUTES, TimeZone.getDefault(), 0, null)
												+ ") is greater than the current end time for the event ("
												+ QommonsUtils.printRelativeTime(event.getEvent().getEndTime().toEpochMilli(),
														System.currentTimeMillis(), QommonsUtils.TimePrecision.MINUTES,
														TimeZone.getDefault(), 0, null)
												+ ")."//
												+ "\nDo you want to clear the end time for this event?")
										.confirm(false)) {
									event.getEvent().setEndTime(null);
								} else {
									return;
								}
							}
							for (Notification not : event.getEvent().getNotifications().getValues()) {
								not.setSnoozeCount(0);
								not.setSnoozeTime(null);
							}
						} finally {
							theEventCallbackLock = false;
						}
						event.getEvent().setInitialTime(time);
					}).asText(FUTURE_DATE_FORMAT);
				});
			} else {
				col.formatText(t -> t == null ? ""
						: QommonsUtils.printRelativeTime(t.toEpochMilli(), System.currentTimeMillis(), QommonsUtils.TimePrecision.MINUTES,
								TimeZone.getDefault(), 0, null));
			}
		});
	}

	private void populateRecurrence(PanelPopulation.TableBuilder<ActiveEvent, ?, ?> table, Container container, boolean mutable) {
		table.withColumn("Recurrence", String.class, n -> n.getEvent().getRecurInterval() == null ? "" : n.getEvent().getRecurInterval(),
				col -> {
					if (mutable) {
						col.withMutation(mut -> mut.mutateAttribute((not, interval) -> {
							not.getEvent().setRecurInterval(interval);
							not.getEvent().getNote().setModified(Instant.now());
							processNotifications();
						}).asText(SpinnerFormat.NUMERICAL_TEXT).filterAccept((el, recur) -> {
							if (recur == null || recur.isEmpty()) {
								return null;
							}

							char lastChar = recur.charAt(recur.length() - 1);
							boolean monthly = false;
							switch (lastChar) {
							case '-':
							case '#':
								monthly = true;
								recur = recur.substring(0, recur.length() - 1).trim();
								break;
							default:
							}
							ParsedDuration d;
							try {
								d = TimeUtils.parseDuration(recur);
							} catch (ParseException e) {
								return "Unrecognized duration: " + recur;
							}
							if (d.signum() <= 0) {
								return "Recurrence must be positive";
							}
							if (monthly) {
								if (d.getComponents().size() != 1 && d.getComponents().get(0).getField() != DurationComponentType.Month) {
									return "'-' and '#' may only be used with monthly duration";
								}
							}
							return null;
						}));
					}
				});
	}

	private void populateNotificationActions(PanelPopulation.TableBuilder<ActiveNotification, ?, ?> table, Container container,
			boolean mutable) {
		table.withColumn("Snoozed", Integer.class, n -> n.getNotification().getSnoozeCount(), col -> col.formatText(s -> {
			switch (s) {
			case 0:
				return "";
			case 1:
				return "Once";
			case 2:
				return "Twice";
			default:
				return s + " times";
			}
		}))//
		.withColumn("Action", String.class, n -> "", col -> col.withMutation(mut -> {
			mut.mutateAttribute((not, action) -> {
				Instant now = Instant.now();
				if (action.startsWith("Snooze")) {
					String durationStr = action.substring("Snooze".length()).trim();
					Duration duration;
					if (durationStr.endsWith("For...")) {
						CellEditor editor = table.getEditor().getCellEditor();
						if (editor != null) {
							editor.cancelCellEditing();
						}
						SettableValue<Duration> durationValue = SettableValue.create(Duration.ofDays(1));
						SimpleObservable<Void> temp = SimpleObservable.build().build();
						ObservableTextField<Duration> durationField = new ObservableTextField<>(durationValue,
								SpinnerFormat.flexDuration(false), temp).setCommitOnType(true).setCommitAdjustmentImmediately(true);
						if (JOptionPane.showConfirmDialog(container, durationField, "Select Snooze Duration",
								JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
							return;
						}
						duration = durationValue.get();
						if (duration == null) {
							return;
						}
						temp.onNext(null);
					} else if (durationStr.endsWith("Until...")) {
						CellEditor editor = table.getEditor().getCellEditor();
						if (editor != null) {
							editor.cancelCellEditing();
						}
						Calendar defaultUntil = TimeUtils.CALENDAR.get();
						defaultUntil.setTimeInMillis(System.currentTimeMillis() + 24L * 60 * 60 * 1000);
						defaultUntil.set(Calendar.MILLISECOND, 0);
						defaultUntil.set(Calendar.SECOND, 0);
						defaultUntil.set(Calendar.MINUTE, 0);
						SettableValue<Instant> untilValue = SettableValue
								.create(Instant.ofEpochMilli(defaultUntil.getTimeInMillis()));
						SimpleObservable<Void> temp = SimpleObservable.build().build();
						ObservableTextField<Instant> untilField = new ObservableTextField<>(untilValue, FUTURE_DATE_FORMAT, temp)
								.setCommitOnType(true).setCommitAdjustmentImmediately(true);
						if (JOptionPane.showConfirmDialog(container, untilField, "Select Snooze Time",
								JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
							return;
						}
						Instant until = untilValue.get();
						if (until == null) {
							return;
						}
						temp.onNext(null);
						if (until.compareTo(now) < 0) {
							JOptionPane.showMessageDialog(container,
									"The selected time (" + FUTURE_DATE_FORMAT.format(until)
									+ ") has already passed.  Please select a time in the future to snooze until",
									"Selected Snooze Time is Past", JOptionPane.ERROR_MESSAGE);
							return;
						}
						not.getNotification().setSnoozeCount(not.getNotification().getSnoozeCount() + 1);
						not.getNotification().setSnoozeTime(until);
						return;
					} else {
						try {
							duration = QommonsUtils.parseDuration(durationStr);
						} catch (ParseException e) {
							e.printStackTrace();
							return;
						}
					}
					not.getNotification().setSnoozeCount(not.getNotification().getSnoozeCount() + 1);
					not.getNotification().setSnoozeTime(now.plus(duration));
				} else if (action.equals("Dismiss")) {
					not.getNotification().setSnoozeTime(null);
					not.getNotification().setSnoozeCount(0);
					not.getNotification().setLastDismiss(now);
				} else if (action.equals("Skip Next")) {
					Instant next;
					theEventCallbackLock = true;
					Instant preNext, preLast;
					try {
						preLast = not.getNotification().getLastDismiss();
						next = preNext = not.getNextNotification();
						if (next == null) {
							return;
						}
						not.getNotification().setLastDismiss(next);
						not.update();
						next = not.getNextNotification();
						not.getNotification().setLastDismiss(preLast); // Restore
						not.update();
						if (next == null) {// ??
							return;
						}
						CellEditor editor = table.getEditor().getCellEditor();
						if (editor != null) {
							editor.cancelCellEditing();
						}
						// TODO notifications don't always have names, so this will look bad
						if (!table
								.alert("Skip Next Alert?", "Skip the next alert for " + not.getNotification().getName() + " at "
										+ QommonsUtils.printRelativeTime(preNext.toEpochMilli(), now.toEpochMilli(),
												TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)
										+ " (" + QommonsUtils.printDuration(TimeUtils.between(now, preNext), false)
										+ " from now)?\n"//
										+ "The next notification would then be at "
										+ QommonsUtils.printRelativeTime(next.toEpochMilli(), now.toEpochMilli(),
												TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)
										+ " (" + QommonsUtils.printDuration(TimeUtils.between(now, next), false) + " from now).")
								.confirm(true)) {
							return;
						}
					} finally {
						theEventCallbackLock = false;
					}
					not.getNotification().setLastDismiss(preNext);
				}
			}).editableIf((not, __) -> {
				if (not.getNextNotification() == null) {
					return false;
				} else if (not.getNextNotification().compareTo(Instant.now()) > 0 && not.getEvent().getRecurrence() == null
						&& not.getNotification().getSnoozeTime() == null) {
					return false;
				} else {
					return true;
				}
			}).asCombo(s -> s, (not, __) -> {
				if (not.getModelValue().getNextNotification().compareTo(Instant.now()) <= 0) {
					return ObservableCollection.of("Dismiss", //
							"Snooze 5 min", "Snooze 30 min", "Snooze 1 hour", "Snooze For...", "Snooze Until...");
				} else if (not.getModelValue().getNotification().getSnoozeTime() != null
						&& not.getModelValue().getNotification().getSnoozeTime().compareTo(Instant.now()) > 0) {
					return ObservableCollection.of("Dismiss");
				} else if (not.getModelValue().getNextNotification() != null
						&& not.getModelValue().getEvent().getRecurrence() != null) {
					return ObservableCollection.of("Skip Next");
				} else {
					return ObservableCollection.of();
				}
			}).clicks(1);
		}));
	}

	private void populateSubjectEditor(PanelPopulator<?, ?> panel, Subject value) {
		ObservableSortedCollection<Note> references = value.getReferences().flow().sorted((n1, n2) -> {
			return -n1.getModified().compareTo(n2.getModified());
		}).collect();

		SettableValue<TableContentControl> filter = SettableValue.create(TableContentControl.DEFAULT);
		panel//
		.addTextField(null, filter, TableContentControl.FORMAT,
				tf -> tf.fill().withTooltip(TableContentControl.TABLE_CONTROL_TOOLTIP)
				.modifyEditor(tf2 -> tf2.setEmptyText("Search...").setCommitOnType(true)))//
		.addTable(references, table -> {
			// Notes not editable here--double click to open them
			table.fill().fillV()//
			.withFiltering(filter)//
			.withItemName("Notes")//
			.withNameColumn(Note::getName, null, false, col -> col.withWidths(50, 120, 300))//
			.withColumn("Updated", Instant.class, Note::getModified, col -> col.withWidths(100, 150, 300))//
			.withColumn("Content", String.class, Note::getContent, col -> col.withWidths(50, 400, 2000))//
			.withMouseListener(new ObservableTableModel.RowMouseAdapter<Note>() {
				@Override
				public void mouseClicked(ModelRow<? extends Note> row, MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
						selectNote(row.getModelValue());
					}
				}
			})//
			.withSelection(theSelectedNote, false)//
			;
		});
	}

	private static class ParsedNotification {
		String name;
		String before;

		ParsedNotification() {
		}

		ParsedNotification(Notification not) {
			name = not.getName();
			before = not.getBefore();
		}

		@Override
		public String toString() {
			if (name != null && !name.isEmpty()) {
				return name + "@" + before;
			} else {
				return before;
			}
		}
	}

	private static final Format<String> RECURRENCE_FORMAT = new Format<String>() {
		@Override
		public void append(StringBuilder text, String value) {
			if (value != null) {
				text.append(value);
			}
		}

		@Override
		public String parse(CharSequence text) throws ParseException {
			TimeUtils.parseDuration(text);
			return text.toString();
		}
	};

	private static Format<ParsedNotification> NOTIFICATION_FORMAT = Format.fielded(ParsedNotification::new, "@", Pattern.compile("@"))//
			.withField("name", Format.TEXT, n -> n.name, f -> f.withDetector(s -> s.toString().indexOf('@')).build((n, nm) -> n.name = nm))//
			.withField("before", RECURRENCE_FORMAT, n -> n.before, f -> f.build((n, b) -> n.before = b))//
			.build();

	private void populateNoteEditor(PanelPopulator<?, ?> panel, Note value) {
		ObservableCollection<ActiveEvent> activeNots = theEvents.flow()//
				.filter(n -> n.getEvent().getNote() == value ? null : "Not in the selected Note")//
				.collect();
		panel.addTextField("Name", EntityReflector.observeField(value, Note::getName)//
				.filterAccept(n -> {
					if (n.length() == 0) {
						return "Note name cannot be empty";
					}
					return null;
				}), SpinnerFormat.NUMERICAL_TEXT, tf -> tf.fill())//
		.addTextArea(null, EntityReflector.observeField(value, Note::getContent), Format.TEXT,
				tf -> tf.fill().fillV().modifyEditor(ta -> ta.withRows(8).setSelectAllOnFocus(false)))//
		.addTable(activeNots, notifications -> {
			notifications.fill().withItemName("Event")//
			.withColumn("Active", boolean.class, n -> n.getEvent().isActive(),
					col -> col.withWidths(10, 40, 50).withMutation(mut -> mut.mutateAttribute((n, a) -> {
						if (a) {
							Instant now = Instant.now();
							if (n.getEvent().getRecurInterval() == null
									&& n.getEvent().getInitialTime().compareTo(now) < 0) {
								if (notifications.alert("Reset Start Time?", "The event's start time time ("
										+ QommonsUtils.printRelativeTime(n.getEvent().getInitialTime().toEpochMilli(),
												System.currentTimeMillis(), QommonsUtils.TimePrecision.MINUTES,
												TimeZone.getDefault(), 0, null)
										+ ") has passed."//
										+ "\nDo you want to reset the start time for this event?").confirm(false)) {
									// Get "now" again because it may have been a while
									n.getEvent().setInitialTime(Instant.now().plus(Duration.ofMinutes(5)));
								} else {
									return;
								}
							}
							if (n.getEvent().getEndTime() != null && now.compareTo(n.getEvent().getEndTime()) >= 0) {
								if (notifications.alert("Clear End Time?",
										"The event's end time time ("
												+ QommonsUtils.printRelativeTime(n.getEvent().getEndTime().toEpochMilli(),
														System.currentTimeMillis(), QommonsUtils.TimePrecision.MINUTES,
														TimeZone.getDefault(), 0, null)
												+ ") has passed."//
												+ "\nDo you want to clear the end time for this event?")
										.confirm(false)) {
									n.getEvent().setEndTime(null);
								} else {
									return;
								}
							}
						}
						n.getEvent().setActive(a);
					}).asCheck()));
			populateEventName(notifications, panel.getContainer(), true);
			populateNextAlertTime(notifications, panel.getContainer(), true);
			populateRecurrence(notifications, panel.getContainer(), true);
			notifications.withColumn("End", Instant.class, n -> n.getEvent().getEndTime(),
					col -> col.withWidths(100, 150, 300).withMutation(mut -> mut.mutateAttribute((not, end) -> {
						not.getEvent().setEndTime(end);
						not.getEvent().getNote().setModified(Instant.now());
					}).asText(FUTURE_DATE_FORMAT)));
			notifications.withColumn("Notifications",
					TypeTokens.get().keyFor(List.class).<List<ParsedNotification>> parameterized(ParsedNotification.class),
					e -> e.getEvent().getNotifications().getValues().stream().map(ParsedNotification::new)
					.collect(Collectors.toList()),
					col -> col.withMutation(mut -> mut.mutateAttribute((e, nots) -> {
						CollectionUtils
						.synchronize(new ArrayList<>(e.getEvent().getNotifications().getValues()), nots,
								(n1, n2) -> Objects.equals(n1.getName(), n2.name))//
						.adjust(new CollectionUtils.CollectionSynchronizer<Notification, ParsedNotification>() {
							@Override
							public boolean getOrder(ElementSyncInput<Notification, ParsedNotification> element) {
								return true;
							}

							@Override
							public ElementSyncAction leftOnly(ElementSyncInput<Notification, ParsedNotification> element) {
								e.getEvent().getNotifications().getValues().remove(element.getLeftValue());
								return element.remove();
							}

							@Override
							public ElementSyncAction rightOnly(ElementSyncInput<Notification, ParsedNotification> element) {
								SyncValueCreator<Notification, Notification> creator = e.getEvent().getNotifications()
										.create()//
										.with(Notification::getName, element.getRightValue().name)//
										.with(Notification::getBefore, element.getRightValue().before)//
										.with(Notification::getLastDismiss, Instant.now());
								if (element.getTargetIndex() < e.getEvent().getNotifications().getValues().size()) {
									creator.before(e.getEvent().getNotifications().getValues()
											.getElement(element.getUpdatedLeftIndex()).getElementId());
								}
								return element.useValue(creator.create().get());
							}

							@Override
							public ElementSyncAction common(ElementSyncInput<Notification, ParsedNotification> element) {
								element.getLeftValue().setName(element.getRightValue().name)//
								.setBefore(element.getRightValue().before);
								return element.preserve();
							}
						}, CollectionUtils.AdjustmentOrder.RightOrder);
					}).asText(new Format.ListFormat<>(NOTIFICATION_FORMAT, ",", null))));
			// populateEventTable(notifications, panel.getContainer(), true);
			notifications.withAdaptiveHeight(2, 4, 10)//
			.withAdd(() -> {
				Calendar time = Calendar.getInstance();
				time.set(Calendar.SECOND, 0);
				time.set(Calendar.MILLISECOND, 0);
				time.add(Calendar.MINUTE, 5);
				Event newNot = value.getEvents().create()//
						.with(Event::getName,
								StringUtils.getNewItemName(value.getEvents().getValues(), Event::getName, "Reminder",
										StringUtils.SIMPLE_DUPLICATES))//
						.with(Event::getInitialTime, Instant.ofEpochMilli(time.getTimeInMillis()))//
						.with(Event::isActive, true)//
						.create().get();
				newNot.getNotifications().create()//
				.with(Notification::getBefore, "0s")//
				.with(Notification::getLastDismiss, Instant.now())//
				.create();
				for (ActiveEvent an : theEventsById.get(value.getId())) {
					if (an.getEvent() == newNot) {
						return an;
					}
				}
				throw new IllegalStateException("No active event added");
			}, null)//
			.withRemove(nots -> value.getEvents().getValues().removeAll(QommonsUtils.map(nots, n -> n.getEvent(), false)),
					null)//
			;
		})//
		;
	}

	private void selectSubject(Subject subject) {
		if (theSelectedSubject.get() != subject) {
			theSelectedSubject.set(subject, null);
		}
		theSubjectSelection.onNext(null);
	}

	private void selectNote(Note note) {
		if (theSelectedNote.get() != note) {
			theSelectedNote.set(note, null);
		}
		theNoteSelection.onNext(null);
	}

	/**
	 * Main method for HypNotiQ
	 *
	 * @param args Command-line arguments, ignored
	 */
	public static void main(String[] args) {
		EventQueue.invokeLater(HypNotiQMain::launchApp);
	}

	/** Launches the HypNotiQ application */
	public static void launchApp() {
		ObservableUiBuilder builder = ObservableSwingUtils.buildUI()//
				.systemLandF()//
				.disposeOnClose(false)//
				.withConfig("hypnotiq")//
				.withTitle("HypNotiQ")//
				.withIcon(HypNotiQMain.class, "/icons/icons8-reminder-48.png")//
				.enableCloseWithoutSave()//
				.withErrorReporting("https://github.com/Updownquark/Misc/issues/new", (str, error) -> {
					if (error) {
						str.append("<ol><li>Describe your issue, what you did to produce it, what effects it had, etc.</li>");
					} else {
						str.append("<ol><li>Describe your issue or feature idea");
					}
					str.append("</li><li>Click \"Submit new issue\"</li></ol>");
				});
		builder.withAbout(HypNotiQMain.class, about -> about.withLatestVersion(() -> {
			Release r;
			try {
				r = new GitHubApiHelper("Updownquark", "Misc").withTagPattern("HypNotiQ-.*").getLatestRelease(HypNotiQMain.class);
			} catch (IOException e) {
				e.printStackTrace(System.out);
				return null;
			}
			return r == null ? null : new AppPopulation.Version(r.getTagName(), r.getName(), r.getDescription());
		}).withUpgrade(version -> {
			try {
				new GitHubApiHelper("Updownquark", "Misc").withTagPattern("HypNotiQ-.*").upgradeToLatest(HypNotiQMain.class,
						builder.getTitle().get(), builder.getIcon().get());
			} catch (IllegalStateException | IOException e) {
				e.printStackTrace(System.out);
			}
		}))//
		.withVisible(SettableValue.create(false))// Not shown initially
		.build((config, onBuilt) -> {
			try {
				new GitHubApiHelper("Updownquark", "Misc").withTagPattern("HypNotiQ-.*").checkForNewVersion(HypNotiQMain.class,
						builder.getTitle().get(), builder.getIcon().get(), release -> {
							String declinedRelease = config.get("declined-release");
							return !release.getTagName().equals(declinedRelease);
						}, release -> config.set("declined-release", release.getTagName()), () -> {
							ObservableConfigParseSession session = new ObservableConfigParseSession();
							ValueHolder<SyncValueSet<Subject>> subjects = new ValueHolder<>();
							ValueHolder<SyncValueSet<Note>> notes = new ValueHolder<>();
							SimpleObservable<Void> built = SimpleObservable.build().build();
							getSubjects(config, session, subjects, notes, built);
							getNotes(config, session, subjects, notes, built);
							built.onNext(null);
							onBuilt.accept(new HypNotiQMain(config, session, subjects.get(), notes.get()));
						});
			} catch (IOException e) {
				// Put this on System.out so we don't trigger the bug warning
				e.printStackTrace(System.out);
			}
		});
	}

	private static void getSubjects(ObservableConfig config, ObservableConfigParseSession session,
			ValueHolder<SyncValueSet<Subject>> subjects, ValueHolder<SyncValueSet<Note>> notes, SimpleObservable<Void> built) {
		config.asValue(Subject.class).at("subjects/subject").withSession(session).asEntity(subjectFormat -> {
			ObservableConfigFormat<Note> noteRefFormat = ObservableConfigFormat.buildReferenceFormat(__ -> notes.get().getValues(), null)
					.withRetrieverReady(() -> notes.get() != null)//
					.withField("id", Note::getId, ObservableConfigFormat.LONG).build();
			subjectFormat.withFieldFormat(Subject::getReferences, ObservableConfigFormat.ofCollection(//
					TypeTokens.get().keyFor(List.class).<List<Note>> parameterized(Note.class), noteRefFormat, "references", "note"));
		}).withBuiltNotifier(built).buildEntitySet(subjects);
	}

	private static void getNotes(ObservableConfig config, ObservableConfigParseSession session, ValueHolder<SyncValueSet<Subject>> subjects,
			ValueHolder<SyncValueSet<Note>> notes, SimpleObservable<Void> built) {
		config.asValue(Note.class).at("notes/note").withSession(session).asEntity(noteFormat -> {
			ObservableConfigFormat<Subject> subjectRefFormat = ObservableConfigFormat
					.buildReferenceFormat(__ -> subjects.get().getValues(), null).withRetrieverReady(() -> subjects.get() != null)//
					.withField("id", Subject::getId, ObservableConfigFormat.LONG).build();
			noteFormat.withFieldFormat(Note::getReferences, ObservableConfigFormat.ofCollection(//
					TypeTokens.get().keyFor(List.class).<List<Subject>> parameterized(Subject.class), subjectRefFormat, "references",
					"subject"));
		}).withBuiltNotifier(built).buildEntitySet(notes);
	}

	/** Tracks the next (or current) alert time for each event */
	public static class ActiveEvent {
		private final Event theEvent;
		private RecurrenceInterval theRecurrence;
		ElementId theElement;
		private final ObservableCollection<ActiveNotification> theNotifications;

		ActiveEvent(Event event) {
			theEvent = event;
			theNotifications = ObservableCollection.create();
			update();
		}

		/** @return The event backing this object */
		public Event getEvent() {
			return theEvent;
		}

		/** @return The recurrence scheme for this event */
		public RecurrenceInterval getRecurrence() {
			return theRecurrence;
		}

		/** @return All notifications configured for this event */
		public ObservableCollection<ActiveNotification> getNotifications() {
			return theNotifications;
		}

		/** @return The next (or current) alert time for this event, or null if no further notifications are scheduled */
		public Instant getNextOccurrence() {
			return getAdjacent(Instant.now(), true, true);
		}

		/**
		 * @param time The time to get the closest event occurrence to
		 * @param after Whether to get the occurrence after or before the given time
		 * @param strict False if the given time is acceptable as a return value, or true if the return value must be strictly after or
		 *        before the given time
		 * @return The closest occurrence time of this event after/before the given time, or null if this even does not occur in that time
		 *         range
		 */
		public Instant getAdjacent(Instant time, boolean after, boolean strict) {
			if (!theEvent.isActive()) {
				return null;
			}
			Instant start = theEvent.getInitialTime();
			if (start == null) {
				return null;
			}
			int comp = start.compareTo(time);
			if (comp == 0) {
				return strict ? null : start;
			} else if (comp < 0 && !after) {
				return null;
			}
			if (theRecurrence == null) {
				return comp > 0 ? start : null;
			} else {
				Instant occur = theRecurrence.getOccurrence(start, time, after, strict);
				if (occur != null && occur.compareTo(start) < 0) {
					occur = start;
				}
				if (occur != null && theEvent.getEndTime() != null && occur.compareTo(theEvent.getEndTime()) > 0) {
					occur = null;
				}
				return occur;
			}
		}

		void update() {
			try {
				theRecurrence = TimeUtils.parseRecurrenceInterval(theEvent.getRecurInterval(), theEvent.getInitialTime());
			} catch (ParseException e) {
				System.out.print(theEvent.getRecurInterval() + ": ");
				e.printStackTrace(System.out);
				theRecurrence = null;
			}
			refreshNext();
			CollectionUtils.synchronize(theNotifications, theEvent.getNotifications().getValues(), (n1, n2) -> n1.getNotification() == n2)
			.simple(n -> new ActiveNotification(this, n)).leftOrder().commonUsesLeft()//
			.onCommon(left -> left.getLeftValue().update())//
			.adjust();
		}

		void refreshNext() {
		}

		@Override
		public String toString() {
			return theEvent.toString();
		}
	}

	/** Wraps a notification of an event and contains methods to determine when it should be shown to the user */
	public static class ActiveNotification implements Comparable<ActiveNotification> {
		private final ActiveEvent theEvent;
		private final Notification theNotification;
		private Instant theEventOccurrence;
		private Instant theNextNotification;
		ElementId theElement;

		/**
		 * @param event The event that the notification is for
		 * @param notification The notification entity to wrap
		 */
		public ActiveNotification(ActiveEvent event, Notification notification) {
			theEvent = event;
			theNotification = notification;
			update();
		}

		/** @return The event occurrence that the current or next occurrence of this notification is for */
		public Instant getEventOccurrence() {
			return theEventOccurrence;
		}

		/** @return The current or next occurrence time of this notification */
		public Instant getNextNotification() {
			return theNextNotification;
		}

		void update() {
			refreshNext();
		}

		void refreshNext() {
			computeNextAlert();
		}

		/** @return The event that this notification is for */
		public ActiveEvent getEvent() {
			return theEvent;
		}

		/** @return The wrapped notification */
		public Notification getNotification() {
			return theNotification;
		}

		@Override
		public int compareTo(ActiveNotification o) {
			// Not quite sure why this would ever happen, but apparently this gets called with a null notification sometimes
			// before it gets removed from the sorted collection
			if (theNextNotification == null) {
				if (o.theNextNotification == null) {
					return 0;
				} else {
					return 1;
				}
			} else if (o.theNextNotification == null) {
				return -1;
			} else {
				return theNextNotification.compareTo(o.theNextNotification);
			}
		}

		private void computeNextAlert() {
			if (!theEvent.getEvent().isActive()) {
				theEventOccurrence = theNextNotification = null;
				return;
			}
			Instant start = theEvent.getEvent().getInitialTime();
			if (start == null) {
				theEventOccurrence = theNextNotification = null;
				return;
			}
			Instant snooze = theNotification.getSnoozeTime();
			Instant lastDismiss = theNotification.getLastDismiss();
			if (snooze != null && (lastDismiss == null || snooze.compareTo(lastDismiss) > 0)) {
				theNextNotification = snooze;
				return;
			}
			ParsedDuration duration;
			try {
				duration = TimeUtils.parseDuration(theNotification.getBefore()).times(-1);
			} catch (ParseException e) {
				System.out.print(theNotification.getBefore() + ": ");
				e.printStackTrace(System.out);
				theEventOccurrence = theNextNotification = null;
				return;
			}
			Instant eventTime, notifyTime;
			if (lastDismiss == null) {
				eventTime = start;
				notifyTime = duration.addTo(eventTime, TimeZone.getDefault());
			} else {
				Instant eventGuessTime = duration.addTo(lastDismiss, TimeZone.getDefault());
				if (theEvent.getRecurrence() == null) {
					if (duration.addTo(start, TimeZone.getDefault()).compareTo(lastDismiss) > 0) {
						eventTime = start;
						notifyTime = duration.addTo(eventTime, TimeZone.getDefault());
					} else {
						eventTime = null;
						notifyTime = null;
					}
				} else {
					if (theEvent.getEvent().getEndTime() != null && eventGuessTime.compareTo(theEvent.getEvent().getEndTime()) > 0) {
						eventGuessTime = theEvent.getEvent().getEndTime();
					}
					eventTime = theEvent.getRecurrence().getOccurrence(start, eventGuessTime, false, false);
					if (eventTime != null) {
						if (eventTime.compareTo(start) < 0) {
							eventTime = start;
						}
						notifyTime = duration.addTo(eventTime, TimeZone.getDefault());
						while (notifyTime.compareTo(lastDismiss) <= 0) {
							eventTime = theEvent.getRecurrence().adjacentOccurrence(eventTime, true);
							if (theEvent.getEvent().getEndTime() != null && eventTime.compareTo(theEvent.getEvent().getEndTime()) > 0) {
								eventTime = null;
								notifyTime = null;
								break;
							} else {
								notifyTime = duration.addTo(eventTime, TimeZone.getDefault());
							}
						}
					} else {
						notifyTime = null;
					}
				}
			}
			theEventOccurrence = eventTime;
			theNextNotification = eventTime == null ? null : duration.addTo(eventTime, TimeZone.getDefault());
		}
	}
}
