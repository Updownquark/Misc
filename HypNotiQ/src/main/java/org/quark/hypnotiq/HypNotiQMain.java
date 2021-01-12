package org.quark.hypnotiq;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.TrayIcon.MessageType;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import javax.swing.BorderFactory;
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
import org.observe.config.ObservableConfig;
import org.observe.config.ObservableConfigFormat;
import org.observe.config.ObservableConfigParseSession;
import org.observe.config.SyncValueSet;
import org.observe.ext.util.GitHubApiHelper;
import org.observe.ext.util.GitHubApiHelper.Release;
import org.observe.util.EntityReflector;
import org.observe.util.TypeTokens;
import org.observe.util.swing.AppPopulation;
import org.observe.util.swing.AppPopulation.ObservableUiBuilder;
import org.observe.util.swing.ModelRow;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.ObservableTableModel;
import org.observe.util.swing.ObservableTextField;
import org.observe.util.swing.PanelPopulation;
import org.observe.util.swing.PanelPopulation.PanelPopulator;
import org.observe.util.swing.TableContentControl;
import org.qommons.QommonsUtils;
import org.qommons.QommonsUtils.TimePrecision;
import org.qommons.StringUtils;
import org.qommons.TimeUtils;
import org.qommons.TimeUtils.DateElementType;
import org.qommons.TimeUtils.ParsedDuration;
import org.qommons.ValueHolder;
import org.qommons.collect.CollectionElement;
import org.qommons.collect.CollectionUtils;
import org.qommons.collect.ElementId;
import org.qommons.io.Format;
import org.qommons.io.SpinnerFormat;
import org.qommons.threading.QommonsTimer;
import org.quark.hypnotiq.entities.Note;
import org.quark.hypnotiq.entities.NoteStatus;
import org.quark.hypnotiq.entities.Notification;
import org.quark.hypnotiq.entities.Subject;

public class HypNotiQMain extends JPanel {
	private final ObservableConfig theConfig;
	private final ObservableConfigParseSession theSession;
	private final SyncValueSet<Subject> theSubjects;
	private final SyncValueSet<Note> theNotes;
	private final ObservableCollection<ActiveNotification> theNotifications;
	private final ObservableSortedCollection<ActiveNotification> theActiveNotifications;
	private final ObservableMultiMap<Long, ActiveNotification> theNotificationsById;
	private final ObservableMap<String, Subject> theSubjectByRefString;
	private final QommonsTimer.TaskHandle theAlertTask;

	private final TrayIcon theTrayIcon;
	private final PopupMenu thePopup;
	private boolean hasSnoozeAll;
	private Duration theReNotifyDuration = Duration.ofMinutes(1);
	private boolean theNotificationCallbackLock;

	private final ObservableCollection<Subject> theEditingSubjects;
	private final ObservableCollection<Note> theEditingNotes;
	private final List<PanelPopulation.TabEditor<?>> theEditingSubjectTabs;
	private final List<PanelPopulation.TabEditor<?>> theEditingNoteTabs;

	private final SettableValue<Subject> theSelectedSubject = SettableValue.build(Subject.class).safe(false).build();
	private final SettableValue<Note> theSelectedNote = SettableValue.build(Note.class).safe(false).build();
	private final SettableValue<ActiveNotification> theSelectedNotification = SettableValue.build(ActiveNotification.class).safe(false)
			.build();
	private final SimpleObservable<Void> theSubjectSelection = SimpleObservable.build().safe(false).build();
	private final SimpleObservable<Void> theNoteSelection = SimpleObservable.build().safe(false).build();
	private final SimpleObservable<Void> theNotificationsSelection = SimpleObservable.build().safe(false).build();

	private PanelPopulation.TabPaneEditor<?, ?> theSubjectTabs;
	private PanelPopulation.TabPaneEditor<?, ?> theNoteTabs;

	public HypNotiQMain(ObservableConfig config, ObservableConfigParseSession session, SyncValueSet<Subject> subjects,
			SyncValueSet<Note> notes) {
		theConfig = config;
		theSession = session;
		theSubjects = subjects;
		theNotes = notes;
		theSubjectByRefString = theSubjects.getValues().reverse().flow().groupBy(String.class, s -> {
			String name = s.getName();
			name = name.replaceAll("\\s", "_");
			return name;
		}, (__, s) -> s).gather().singleMap(true);
		theNotifications = ObservableCollection.build(ActiveNotification.class).safe(false).build();
		theActiveNotifications = theNotifications.flow().filter(n -> n.getNextAlertTime() == null ? "Not Active" : null)
				.sorted(ActiveNotification::compareTo).collect();
		theNotificationsById = theActiveNotifications.flow().groupBy(long.class, n -> n.getNotification().getNote().getId(), (id, n) -> n)
				.gather();
		theEditingSubjects = ObservableCollection.build(Subject.class).safe(false).build();
		theEditingNotes = ObservableCollection.build(Note.class).safe(false).build();
		theEditingSubjectTabs = new ArrayList<>();
		theEditingNoteTabs = new ArrayList<>();

		for (Note note : theNotes.getValues()) {
			for (Notification notification : note.getNotifications().getValues()) {
				ActiveNotification an = new ActiveNotification(notification);
				an.theElement = theNotifications.addElement(an, false).getElementId();
			}
		}
		theAlertTask = QommonsTimer.getCommonInstance().build(this::processNotifications, null, false).onEDT();
		// Watch for notification changes
		theConfig.watch(theConfig.buildPath(ObservableConfig.ANY_NAME).multi(true).build()).act(evt -> {
			if (theNotificationCallbackLock) {
				return;
			}
			theNotificationCallbackLock = true;
			try {
				boolean terminal = true;
				for (ObservableConfig target : evt.relativePath.reverse()) {
					if (target.getName().equals("notification")) {
						Notification notification = (Notification) target.getParsedItem(theSession);
						if (notification == null) {
							return;
						}
						ActiveNotification found = null;
						for (ActiveNotification an : theNotificationsById.get(notification.getNote().getId())) {
							if (an.getNotification() == notification) {
								found = an;
								break;
							}
						}
						if (found != null) {
							if (terminal && evt.changeType == CollectionChangeType.remove) {
								theNotifications.mutableElement(found.theElement).remove();
							} else {
								found.refresh();
								theNotifications.mutableElement(found.theElement).set(found); // Update
							}
						} else if (evt.changeType != CollectionChangeType.remove) {
							found = new ActiveNotification(notification);
							found.theElement = theNotifications.addElement(found, false).getElementId();
						}
						break;
					} else if (terminal && evt.changeType == CollectionChangeType.remove && target.getName().equals("note")) {
						if (evt.changeType != CollectionChangeType.remove) {
							return;
						}
						Note note = (Note) target.getParsedItem(theSession);
						if (note == null) {
							return;
						}
						for (Notification notification : note.getNotifications().getValues()) {
							for (ActiveNotification an : theActiveNotifications) {
								if (an.getNotification() == notification) {
									theNotifications.mutableElement(an.theElement).remove();
								}
							}
						}
						break;
					}
					terminal = false;
				}
			} finally {
				theNotificationCallbackLock = false;
			}
		});
		theActiveNotifications.simpleChanges().act(__ -> {
			if (!theNotificationCallbackLock) {
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
			theNotificationsSelection.onNext(null);
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
			SystemTray.getSystemTray().remove(theTrayIcon);
		}, "HypNotiQ Shutdown"));

		theNotes.getValues().onChange(evt -> {
			if (evt.getType() != CollectionChangeType.set) {
				return;
			}
			String content = evt.getNewValue().getContent();
			Set<Subject> refs = new LinkedHashSet<>();
			StringBuilder refName = new StringBuilder();
			boolean isRef = false;
			for (int c = 0; c < content.length(); c++) {
				if (isRef) {
					if (Character.isWhitespace(content.charAt(c))) {
						Subject sub = theSubjectByRefString.get(refName.toString());
						if (sub != null) {
							refs.add(sub);
						}
						isRef = false;
						refName.setLength(0);
					} else {
						refName.append(content.charAt(c));
					}
				} else if (content.charAt(c) == '@') {
					isRef = true;
				}
			}
			boolean[] modified = new boolean[1];
			CollectionUtils.synchronize(evt.getNewValue().getReferences(), new ArrayList<>(refs))//
					.simple(s -> s).rightOrder().commonUsesLeft().onLeft(left -> {
						modified[0] = true;
						left.getLeftValue().getReferences().remove(evt.getNewValue());
					}).onRight(right -> {
						modified[0] = true;
						int index = Collections.binarySearch(right.getRightValue().getReferences(), evt.getNewValue(), (note1, note2) -> {
							return note2.getOccurred().compareTo(note1.getOccurred());
						});
						if (index < 0) {
							right.getRightValue().getReferences().add(-index - 1, evt.getNewValue());
						} else {
							right.getRightValue().getReferences().add(index, evt.getNewValue());
						}
					}).adjust();
		});

		initComponents();

		processNotifications();
	}

	public SyncValueSet<Note> getNotes() {
		return theNotes;
	}

	public ObservableConfig getConfig() {
		return theConfig;
	}

	private void processNotifications() {
		if (theNotificationCallbackLock) {
			return;
		}
		theNotificationCallbackLock = true;
		try {
			Instant now = Instant.now();
			Instant nextAlert = null;
			List<ActiveNotification> currentNotifications = new LinkedList<>();
			for (CollectionElement<ActiveNotification> notification : theActiveNotifications.elements()) {
				if (notification.get().getNextAlertTime().compareTo(now) > 0) {
					nextAlert = notification.get().getNextAlertTime();
					break;
				}
				if (currentNotifications.add(notification.get())) {
					theActiveNotifications.mutableElement(notification.getElementId()).set(notification.get()); // Update
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
				theTrayIcon.displayMessage(notification.getNotification().getNote().getName(), notification.getNotification().getName(),
						MessageType.INFO);
			} else {
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
						msg.append(not.getNotification().getName());
					}
				}
				theTrayIcon.setToolTip("HypNotiQ: " + currentNotifications.size() + " current reminders");
				theTrayIcon.displayMessage(currentNotifications.size() + " Notifications", msg.toString(), MessageType.INFO);
			}

			if (!currentNotifications.isEmpty() && (nextAlert == null || nextAlert.compareTo(now.plus(theReNotifyDuration)) > 0)) {
				nextAlert = now.plus(theReNotifyDuration);
				if (!hasSnoozeAll) {
					MenuItem item = new MenuItem("Snooze All 5 min");
					item.addActionListener(evt -> {
						theNotificationCallbackLock = true;
						try {
							for (ActiveNotification not : currentNotifications) {
								not.getNotification().setSnoozeCount(not.getNotification().getSnoozeCount() + 1);
								not.getNotification().setSnoozeTime(now.plus(Duration.ofMinutes(5)));
								not.refresh();
							}
						} finally {
							theNotificationCallbackLock = false;
						}
						processNotifications();
					});
					thePopup.add(item);
				}
			} else if (hasSnoozeAll) {
				for (int i = 0; i < thePopup.getItemCount(); i++) {
					String name = thePopup.getItem(i).getName();
					if (name.equals("Snooze All 5 min")) {
						thePopup.remove(i);
						break;
					}
				}
			}

			if (nextAlert != null) {
				theAlertTask.runNextAt(nextAlert);
			} else {
				theAlertTask.setActive(false);
			}
		} finally {
			theNotificationCallbackLock = false;
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
							notificationsTab -> notificationsTab.setName("Notifications").selectOn(theNotificationsSelection))//
			;
		})//
		;
		theEditingSubjects.onChange(evt -> {
			switch (evt.getType()) {
			case add:
				theSubjectTabs.withVTab(evt.getNewValue().getId(), subjectPanel -> populateSubjectEditor(subjectPanel, evt.getNewValue()),
						subjectTab -> {
							theEditingSubjectTabs.add(evt.getIndex(), subjectTab);
							subjectTab.setName(SettableValue.build(String.class).safe(false).withValue(evt.getNewValue().getName()).build())
									.setRemovable(true).onRemove(__ -> {
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
					noteTab.setName(SettableValue.build(String.class).safe(false).withValue(evt.getNewValue().getName()).build())
							.setRemovable(true).onRemove(__ -> {
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
		SettableValue<TableContentControl> filter = SettableValue.build(TableContentControl.class).safe(false)
				.withValue(TableContentControl.DEFAULT).build();
		subjectsTab//
				.addTextField(null, filter, TableContentControl.FORMAT,
						tf -> tf.fill().withTooltip(TableContentControl.TABLE_CONTROL_TOOLTIP)
								.modifyEditor(tf2 -> tf2.setEmptyText("Search...")))//
				.addTable(theSubjects.getValues(), table -> {
					table.fill().fillV()//
							.withItemName("Subject")//
							.withNameColumn(Subject::getName, Subject::setName, true, col -> col.withWidths(50, 120, 300))//
							.withColumn("Last Mentioned", Instant.class, subject -> {
								if (subject.getReferences().isEmpty()) {
									return null;
								}
								return subject.getReferences().get(0).getOccurred();
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
							.withAdd(() -> {
								// Don't select the subject--it doesn't have anything in the timeline, so just edit it here inline
								return theSubjects.create()//
										.with(Subject::getName,
												StringUtils.getNewItemName(theSubjects.getValues(), Subject::getName, "New Subject",
														StringUtils.SIMPLE_DUPLICATES))//
										.create().get();
							}, null)//
							.withRemove(subjects -> {
								// TODO Not the best, since deleting multiple subjects might leave more "orphaned" notes
								List<Note> notes = new ArrayList<>();
								for (Subject subject : subjects) {
									for (Note ref : subject.getReferences()) {
										if (ref.getReferences().size() == 1) {
											notes.add(ref);
										}
									}
								}
								int answer = JOptionPane.showConfirmDialog(subjectsTab.getContainer(), "Delete Referencing Notes?",
										"Subject(s) are referred to (exclusively) by " + notes.size() + " notes.  Delete them as well?",
										JOptionPane.YES_NO_CANCEL_OPTION);
								switch (answer) {
								case JOptionPane.YES_OPTION:
									for (Subject subject : subjects) {
										for (Note ref : subject.getReferences()) {
											if (ref.getReferences().size() == 1) {
												theNotes.getValues().remove(ref);
											} else {
												scrubReferences(ref, subject);
											}
										}
									}
									break;
								case JOptionPane.NO_OPTION:
									for (Subject subject : subjects) {
										for (Note ref : subject.getReferences()) {
											scrubReferences(ref, subject);
										}
									}
									break;
								default:
									return;
								}

								theSubjects.getValues().removeAll(subjects);
							}, mod -> mod.confirmForItems("Confirm Subject Deletion", "Permanently delete", "?", true))//
					;
				});
	}

	private void populateNotesTab(PanelPopulator<?, ?> notesTab) {
		SettableValue<TableContentControl> filter = SettableValue.build(TableContentControl.class).safe(false)
				.withValue(TableContentControl.DEFAULT).build();
		notesTab//
				.addTextField(null, filter, TableContentControl.FORMAT,
						tf -> tf.fill().withTooltip(TableContentControl.TABLE_CONTROL_TOOLTIP)
								.modifyEditor(tf2 -> tf2.setEmptyText("Search...")))//
				.addTable(theNotes.getValues(), table -> {
					table.fill().fillV()//
							.withItemName("Notes")//
							.withNameColumn(Note::getName, (note, name) -> {
								note.setName(name);
								note.setModified(Instant.now());
							}, false, col -> col.withWidths(50, 120, 300))//
							.withColumn("Occurred", Instant.class, Note::getOccurred,
									col -> col.withMutation(mut -> mut.mutateAttribute((note, occurred) -> {
										note.setOccurred(occurred);
										note.setModified(Instant.now());
									}).asText(SpinnerFormat.flexDate(Instant::now, "EEE MMM dd, yyyy", TimeZone.getDefault(),
											TimeUtils.DateElementType.Second, false))).withWidths(100, 150, 300))//
							.withColumn("Content", String.class, Note::getContent, null)//
							.withColumn("References", String.class, //
									note -> StringUtils.print(", ", note.getReferences(), String::valueOf).toString(), null)//
							.withColumn("Next Alert Time", Instant.class, note -> {
								Instant soonest = null;
								for (ActiveNotification notification : theNotificationsById.get(note.getId())) {
									if (soonest == null || notification.getNextAlertTime().compareTo(soonest) < 0) {
										soonest = notification.getNextAlertTime();
									}
								}
								return soonest;
							}, col -> col.withWidths(100, 150, 300)
									.formatText(t -> t == null ? ""
											: QommonsUtils.printRelativeTime(t.toEpochMilli(), System.currentTimeMillis(),
													QommonsUtils.TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)))//
							.withColumn("Status", NoteStatus.class, Note::getStatus,
									col -> col.withMutation(mut -> mut.mutateAttribute((note, status) -> {
										note.setStatus(status);
										note.setModified(Instant.now());
									}).asCombo(NoteStatus::name, ObservableCollection.of(NoteStatus.class, NoteStatus.values()))))//
							.withColumn("Noted", Instant.class, Note::getNoted,
									col -> col.withWidths(100, 150, 300)
											.withMutation(mut -> mut.asText(SpinnerFormat.flexDate(Instant::now, "EEE MMM dd, yyyy",
													TimeZone.getDefault(), TimeUtils.DateElementType.Second, false))))//
							.withColumn("Modified", Instant.class, Note::getNoted,
									col -> col.withWidths(100, 150, 300)
											.withMutation(mut -> mut.asText(SpinnerFormat.flexDate(Instant::now, "EEE MMM dd, yyyy",
													TimeZone.getDefault(), TimeUtils.DateElementType.Second, false))))//
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
										.with(Note::getNoted, now)//
										.with(Note::getOccurred, now)//
										.with(Note::getModified, now)//
										.with(Note::getStatus, NoteStatus.Waiting)//
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
		SettableValue<TableContentControl> filter = SettableValue.build(TableContentControl.class).safe(false)
				.withValue(TableContentControl.DEFAULT).build();
		notificationsTab//
				.addTextField(null, filter, TableContentControl.FORMAT,
						tf -> tf.fill().withTooltip(TableContentControl.TABLE_CONTROL_TOOLTIP)
								.modifyEditor(tf2 -> tf2.setEmptyText("Search...")))//
				.addTable(theActiveNotifications, table -> {
					table.fill().fillV()//
							.withItemName("Notification")//
							.withSelection(theSelectedNotification, false)//
							.withColumn("Note", String.class, not -> not.getNotification().getNote().getName(), null)//
					;
					populateNotificationTable(table, notificationsTab.getContainer());
					table.withMouseListener(new ObservableTableModel.RowMouseAdapter<ActiveNotification>() {
						@Override
						public void mouseClicked(ModelRow<? extends ActiveNotification> row, MouseEvent e) {
							if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
								selectNote(row.getModelValue().getNotification().getNote());
							}
						}
					})//
					;
				});
	}

	private void populateNotificationTable(PanelPopulation.TableBuilder<ActiveNotification, ?> table, Container container) {
		table//
				.withNameColumn(n -> n.getNotification().getName(), (not, name) -> {
					not.getNotification().setName(name);
					not.getNotification().getNote().setModified(Instant.now());
				}, false, col -> col.withMutation(mut -> mut.filterAccept((not, name) -> {
					if (StringUtils.isUniqueName(not.get().getNotification().getNote().getNotifications().getValues(),
							Notification::getName, name, not.get().getNotification())) {
						return null;
					}
					return not.get().getNotification().getNote().getName() + " already has a notification named \"" + name + "\"";
				})))//
				.withColumn("Next Alert", Instant.class, ActiveNotification::getNextAlertTime,
						col -> col.withWidths(100, 150, 300).withMutation(mut -> {
							mut.mutateAttribute((not, time) -> {
								not.getNotification().setInitialTime(time);
								not.getNotification().getNote().setModified(Instant.now());
							}).asText(SpinnerFormat.flexDate(Instant::now, "EEE MMM dd, yyyy", TimeZone.getDefault(),
									DateElementType.Second, false));
						}).decorate((cell, deco) -> {
							boolean notified = cell.getModelValue().getNextAlertTime().compareTo(Instant.now()) <= 0;
							if (notified) {
								deco.withBorder(BorderFactory.createLineBorder(Color.red));
							}
						}))//
				.withColumn("Recurrence", ParsedDuration.class, n -> {
					if (n.getNotification().getRecurInterval() == null) {
						return null;
					}
					try {
						return TimeUtils.parseDuration(n.getNotification().getRecurInterval());
					} catch (ParseException e) {
						e.printStackTrace();
						return null;
					}
				}, col -> col.withMutation(mut -> mut.mutateAttribute((not, interval) -> {
					not.getNotification().setRecurInterval(interval.toString());
					not.getNotification().getNote().setModified(Instant.now());
					processNotifications();
				}).asText(SpinnerFormat.forAdjustable(TimeUtils::parseDuration)).filterAccept((el, d) -> {
					if (d.signum() <= 0) {
						return "Recurrence must be positive";
					}
					return null;
				})))//
				.withColumn("End", Instant.class, n -> n.getNotification().getEndTime(),
						col -> col.withWidths(100, 150, 300).withMutation(mut -> mut.mutateAttribute((not, end) -> {
							not.getNotification().setEndTime(end);
							not.getNotification().getNote().setModified(Instant.now());
						}).asText(SpinnerFormat.flexDate(Instant::now, "EEE MMM dd yyyy", TimeZone.getDefault(), DateElementType.Second,
								false))))
				.withColumn("Snoozed", Integer.class, n -> n.getNotification().getSnoozeCount(), col -> col.formatText(s -> {
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
				.withColumn("Last Alert", Instant.class, n -> n.getNotification().getLastAlertTime(),
						col -> col.withWidths(100, 150, 300)
								.formatText(t -> t == null ? "Never"
										: QommonsUtils.printRelativeTime(t.toEpochMilli(), System.currentTimeMillis(),
												QommonsUtils.TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)))//
				.withColumn("Action", String.class, n -> "", col -> col.withMutation(mut -> {
					mut.mutateAttribute((not, action) -> {
						Instant now = Instant.now();
						if (action.startsWith("Snooze")) {
							String durationStr = action.substring("Snooze".length()).trim();
							Duration duration;
							if (durationStr.equals("...")) {
								SettableValue<Duration> durationValue = SettableValue.build(Duration.class).safe(false).build();
								SimpleObservable<Void> temp = SimpleObservable.build().safe(false).build();
								ObservableTextField<Duration> durationField = new ObservableTextField<>(durationValue,
										SpinnerFormat.flexDuration(false), temp);
								durationStr = JOptionPane.showInputDialog(container, durationField, "Select Snooze Time",
										JOptionPane.QUESTION_MESSAGE);
								if (durationStr == null || durationStr.isEmpty()) {
									return;
								}
								duration = durationValue.get();
								temp.onNext(null);
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
							not.getNotification().setLastAlertTime(now);
						} else if (action.equals("Skip Next")) {
							theNotificationCallbackLock = true;
							try {
								Instant preLast = not.getNotification().getLastAlertTime();
								not.getNotification().setLastAlertTime(not.getNextAlertTime());
								not.refresh();
								not.getNotification().setLastAlertTime(preLast);
								if (table.alert("Skip Next Alert?", "Skip the next alert for " + not.getNotification().getName() + " at "
										+ QommonsUtils.printRelativeTime(not.getNextAlertTime().toEpochMilli(), now.toEpochMilli(),
												TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)
										+ " (" + QommonsUtils.printDuration(TimeUtils.between(now, not.getNextAlertTime()), false)
										+ " from now)?\n"//
										+ "The next alert would then be at "
										+ QommonsUtils.printRelativeTime(not.getNextAlertTime().toEpochMilli(), now.toEpochMilli(),
												TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)
										+ " (" + QommonsUtils.printDuration(TimeUtils.between(now, not.getNextAlertTime()), false)
										+ " from now).").confirm(true)) {
									not.getNotification().setInitialTime(not.getNextAlertTime());
									not.refresh();
								} else {
									return;
								}
							} finally {
								theNotificationCallbackLock = false;
							}
						}
						not.refresh();
						theNotifications.mutableElement(not.theElement).set(not);
						processNotifications();
					}).asCombo(s -> s, (not, __) -> {
						if (not.getModelValue().getNextAlertTime().compareTo(Instant.now()) <= 0) {
							return ObservableCollection.of(String.class, "Snooze 5 min", "Snooze 30 min", "Snooze 1 hour", "Snooze...",
									"Dismiss");
						} else if (not.getModelValue().getNotification().getSnoozeTime() != null
								&& not.getModelValue().getNotification().getSnoozeTime().compareTo(Instant.now()) > 0) {
							return ObservableCollection.of(String.class, "Dismiss");
						} else {
							return ObservableCollection.of(String.class, "Skip Next");
						}
					}).clicks(1);
				}))//
		;
	}

	private void populateSubjectEditor(PanelPopulator<?, ?> panel, Subject value) {
		// TODO Timeline
		// As vertically-laid out cards
		// entries of type "Note" can be edited
	}

	private void populateNoteEditor(PanelPopulator<?, ?> panel, Note value) {
		ObservableSortedCollection<ActiveNotification> activeNots = theActiveNotifications.flow()//
				.filter(n -> n.getNotification().getNote() == value ? null : "Not in the selected Note").collect();
		panel.addTextField("Name", EntityReflector.observeField(value, Note::getName)//
				.filterAccept(n -> {
					if (n.length() == 0) {
						return "Note name cannot be empty";
					}
					return null;
				}), SpinnerFormat.NUMERICAL_TEXT, tf -> tf.fill())//
				.addComboField("Status", EntityReflector.observeField(value, Note::getStatus), null, NoteStatus.values())//
				.addTextArea(null, EntityReflector.observeField(value, Note::getContent), Format.TEXT,
						tf -> tf.fill().fillV().modifyEditor(ta -> ta.withRows(8)))//
				.addTable(activeNots, notifications -> {
					notifications.fill().withItemName("Notification");
					populateNotificationTable(notifications, panel.getContainer());
					notifications.withAdaptiveHeight(2, 2, 5);
					notifications.withAdd(() -> {
						Calendar time = Calendar.getInstance();
						time.set(Calendar.SECOND, 0);
						time.set(Calendar.MILLISECOND, 0);
						time.add(Calendar.MINUTE, 5);
						Notification newNot = value.getNotifications().create()//
								.with(Notification::getName,
										StringUtils.getNewItemName(theSelectedNote.get().getNotifications().getValues(),
												Notification::getName, "Reminder", StringUtils.SIMPLE_DUPLICATES))//
								.with(Notification::getInitialTime, Instant.ofEpochMilli(time.getTimeInMillis()))//
								.with(Notification::isActive, true)//
								.create().get();
						for (ActiveNotification an : theActiveNotifications) {
							if (an.getNotification() == newNot) {
								return an;
							}
						}
						throw new IllegalStateException("No active notification added");
					}, null)//
							.withRemove(null, null)//
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

	private static void scrubReferences(Note note, Subject reference) {
		int todo = todo;// TODO
	}

	public static void main(String[] args) {
		ObservableUiBuilder builder = ObservableSwingUtils.buildUI()//
				.disposeOnClose(false)//
				.withConfig("hypnotiq").withConfigAt("HypNotiQ.xml")//
				.saveOnMod(true).saveEvery(Duration.ofMinutes(5))//
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
				new GitHubApiHelper("Updownquark", "Misc").withTagPattern("Tasq-.*").upgradeToLatest(HypNotiQMain.class,
						builder.getTitle().get(), builder.getIcon().get());
			} catch (IllegalStateException | IOException e) {
				e.printStackTrace(System.out);
			}
		})).withBackups(backups -> backups.withBackupSize(1_000_000, 100_000_000).withDuration(Duration.ofDays(1), Duration.ofDays(30))
				.withBackupCount(10, 100))//
				.systemLandF()//
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
									getSubjects(config, session, subjects, notes);
									getNotes(config, session, subjects, notes);
									onBuilt.accept(new HypNotiQMain(config, session, subjects.get(), notes.get()));
								});
					} catch (IOException e) {
						// Put this on System.out so we don't trigger the bug warning
						e.printStackTrace(System.out);
					}
				});
	}

	private static void getSubjects(ObservableConfig config, ObservableConfigParseSession session,
			ValueHolder<SyncValueSet<Subject>> subjects, ValueHolder<SyncValueSet<Note>> notes) {
		config.asValue(Subject.class).at("subjects/subject").withSession(session).asEntity(subjectFormat -> {
			ObservableConfigFormat<Note> noteRefFormat = ObservableConfigFormat.buildReferenceFormat(__ -> notes.get().getValues(), null)
					.withField("id", Note::getId, ObservableConfigFormat.LONG).build();
			subjectFormat.withFieldFormat(Subject::getReferences, ObservableConfigFormat.ofCollection(//
					TypeTokens.get().keyFor(List.class).<List<Note>> parameterized(Note.class), noteRefFormat, "references", "note"));
		}).buildEntitySet(subjects);
	}

	private static void getNotes(ObservableConfig config, ObservableConfigParseSession session, ValueHolder<SyncValueSet<Subject>> subjects,
			ValueHolder<SyncValueSet<Note>> notes) {
		config.asValue(Note.class).at("notes/note").withSession(session).asEntity(noteFormat -> {
			ObservableConfigFormat<Subject> subjectRefFormat = ObservableConfigFormat
					.buildReferenceFormat(__ -> subjects.get().getValues(), null)
					.withField("id", Subject::getId, ObservableConfigFormat.LONG).build();
			noteFormat.withFieldFormat(Note::getReferences, ObservableConfigFormat.ofCollection(//
					TypeTokens.get().keyFor(List.class).<List<Subject>> parameterized(Subject.class), subjectRefFormat, "references",
					"subject"));
		}).buildEntitySet(notes);
	}

	public static class ActiveNotification implements Comparable<ActiveNotification> {
		private final Notification theNotification;
		ElementId theElement;
		private Instant theNextAlertTime;

		public ActiveNotification(Notification notification) {
			theNotification = notification;
			theNextAlertTime = getNextAlertTime(notification);
		}

		public Notification getNotification() {
			return theNotification;
		}

		public Instant getNextAlertTime() {
			return theNextAlertTime;
		}

		void refresh() {
			theNextAlertTime = getNextAlertTime(theNotification);
		}

		@Override
		public int compareTo(ActiveNotification o) {
			return theNextAlertTime.compareTo(o.theNextAlertTime);
		}

		private static Instant getNextAlertTime(Notification notification) {
			if (!notification.isActive()) {
				return null;
			}
			Instant lastAlert = notification.getLastAlertTime();
			Instant start = notification.getInitialTime();
			Instant snooze = notification.getSnoozeTime();
			if (snooze != null && (lastAlert == null || snooze.compareTo(lastAlert) > 0)) {
				return snooze;
			}
			if (lastAlert == null) {
				return start;
			}
			String recur = notification.getRecurInterval();
			if (recur == null) {
				return null;
			}
			ParsedDuration duration;
			try {
				duration = TimeUtils.parseDuration(recur);
			} catch (ParseException e) {
				e.printStackTrace();
				return null;
			}
			Instant nextAlert;
			if (lastAlert.equals(start)) {
				nextAlert = duration.addTo(start, TimeZone.getDefault());
			} else {
				Duration estDuration = duration.asDuration();
				int times = TimeUtils.divide(Duration.between(start, lastAlert), estDuration);
				if (times == 0) {
					nextAlert = duration.addTo(start, TimeZone.getDefault());
				} else {
					if (times <= 2) {
						nextAlert = duration.addTo(start, TimeZone.getDefault());
					} else {
						nextAlert = duration.times(times - 1).addTo(start, TimeZone.getDefault());
					}
					while (nextAlert.compareTo(lastAlert) < 0) {
						nextAlert = duration.addTo(nextAlert, TimeZone.getDefault());
					}
				}
			}
			if (notification.getEndTime() != null && nextAlert.compareTo(notification.getEndTime()) > 0) {
				nextAlert = null;
				notification.setActive(false);
			}
			return nextAlert;
		}
	}
}
