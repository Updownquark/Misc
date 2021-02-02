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
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.observe.config.ObservableConfig;
import org.observe.config.ObservableConfigFormat;
import org.observe.config.ObservableConfigFormat.EntityConfigFormat;
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
import org.qommons.TimeUtils.DurationComponentType;
import org.qommons.TimeUtils.ParsedDuration;
import org.qommons.ValueHolder;
import org.qommons.collect.CollectionElement;
import org.qommons.collect.CollectionUtils;
import org.qommons.collect.ElementId;
import org.qommons.io.Format;
import org.qommons.io.SpinnerFormat;
import org.qommons.threading.QommonsTimer;
import org.quark.hypnotiq.entities.Note;
import org.quark.hypnotiq.entities.Notification;
import org.quark.hypnotiq.entities.Subject;

/** A note-taking app that facilitates very flexible, very persistent notifications */
public class HypNotiQMain extends JPanel {
	private static final SpinnerFormat<Instant> FUTURE_DATE_FORMAT = SpinnerFormat.flexDate(Instant::now, "EEE MMM dd, yyyy",
			opts -> opts.withMaxResolution(TimeUtils.DateElementType.Second).withEvaluationType(TimeUtils.RelativeTimeEvaluation.FUTURE));
	private static final SpinnerFormat<Instant> PAST_DATE_FORMAT = SpinnerFormat.flexDate(Instant::now, "EEE MMM dd, yyyy",
			opts -> opts.withMaxResolution(TimeUtils.DateElementType.Second).withEvaluationType(TimeUtils.RelativeTimeEvaluation.PAST));
	private static final Pattern SUBJECT_PATTERN=Pattern.compile("\\#"//
			+ "[a-zA-Z_$&()~:;\\[\\]\\{\\}|\\\\.\\<\\>\\?0-9]*"//
			+ "[a-zA-Z_$&()~:;\\[\\]\\{\\}|\\\\.\\<\\>\\?/]*");

	private final ObservableConfig theConfig;
	private final ObservableConfigParseSession theSession;
	private final SyncValueSet<Subject> theSubjects;
	private final SyncValueSet<Note> theNotes;
	private final ObservableCollection<ActiveNotification> theNotifications;
	private final ObservableSortedCollection<ActiveNotification> theActiveNotifications;
	private final ObservableMultiMap<Long, ActiveNotification> theNotificationsById;
	private final ObservableMap<String, Subject> theSubjectByName;
	private final QommonsTimer.TaskHandle theAlertTask;

	private final TrayIcon theTrayIcon;
	private final PopupMenu thePopup;
	private final MenuItem theSnoozeAllItem;
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

	/**
	 * @param config
	 *            The config to store UI settings and the source of the entities
	 * @param session
	 *            The parse session used for entity parsing
	 * @param subjects
	 *            The configured {@link Subject} set
	 * @param notes
	 *            The configured {@link Note} set
	 */
	public HypNotiQMain(ObservableConfig config, ObservableConfigParseSession session, SyncValueSet<Subject> subjects,
			SyncValueSet<Note> notes) {
		theConfig = config;
		theSession = session;
		theSubjects = subjects;
		theNotes = notes;
		theSubjectByName = theSubjects.getValues().reverse().flow()
				.groupBy(String.class, s->s.getName().toLowerCase(), (__, s) -> s).gather().singleMap(true);
		theNotifications = ObservableCollection.build(ActiveNotification.class).safe(false).build();
		theActiveNotifications = theNotifications.flow().filter(n -> n.getNextAlertTime() == null ? "Not Active" : null)
				.sorted(ActiveNotification::compareTo).collect();
		theNotificationsById = theNotifications.flow().groupBy(long.class, n -> n.getNotification().getNote().getId(), (id, n) -> n)
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
		// Watch for entity changes
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
						if (notification == null || EntityConfigFormat.getConfig(notification) != target) {
							return;
						}
						ActiveNotification found = null;
						long id = notification.getNote().getId();
						for (ActiveNotification an : theNotificationsById.get(id)) {
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
						Note note = (Note) target.getParsedItem(theSession);
						if (note == null || EntityConfigFormat.getConfig(note) != target) {
							continue;
						}
						for (Notification notification : note.getNotifications().getValues()) {
							for (ActiveNotification an : theActiveNotifications) {
								if (an.getNotification() == notification) {
									theNotifications.mutableElement(an.theElement).remove();
								}
							}
						}
						for (Subject ref : note.getReferences()) {
							ref.getReferences().remove(note);
						}
						break;
					} else if (terminal && evt.changeType == CollectionChangeType.remove && target.getName().equals("subject")) {
						//TODO Need this now?
						Subject subject = (Subject) target.getParsedItem(theSession);
						if (subject == null || EntityConfigFormat.getConfig(subject) != target) {
							continue;
						}
						for (Note ref : subject.getReferences()) {
							scrubReferences(ref, subject);
						}
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
			EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(theTrayIcon));
		}, "HypNotiQ Shutdown"));

		theNotes.getValues().onChange(evt -> {
			if (evt.getType() != CollectionChangeType.set) {
				return;
			}
			String content = evt.getNewValue().getContent();
			Set<String> refNames = new LinkedHashSet<>();
			Matcher subjectMatch=SUBJECT_PATTERN.matcher(content);
			while(subjectMatch.find()) {
				String subjectName=subjectMatch.group().substring(1).toLowerCase();
				refNames.add(subjectName);
			}
			boolean[] modified = new boolean[1];
			CollectionUtils
					.<Subject, String>synchronize(evt.getNewValue().getReferences(), new ArrayList<>(refNames),
							(sub, name) -> sub.getName().equals(name))//
					.simple(s -> {
						Subject sub=theSubjectByName.get(s);
						if(sub==null) {
							sub=theSubjects.create()//
									.with(Subject::getName, s)//
									.create().get();
						}
						sub.getReferences().add(evt.getNewValue());
						return sub;
					}).rightOrder().commonUsesLeft().onLeft(left -> {
						modified[0] = true;
						left.getLeftValue().getReferences().remove(evt.getNewValue());
						if(left.getLeftValue().getReferences().isEmpty())
							theSubjects.getValues().remove(left.getLeftValue());
					}).onRight(right -> {
						modified[0] = true;
					}).adjust();
		});
		theSnoozeAllItem = new MenuItem("Snooze All 5 min");
		theSnoozeAllItem.addActionListener(evt -> {
			theNotificationCallbackLock = true;
			try {
				Instant now = Instant.now();
				for (ActiveNotification not : theActiveNotifications) {
					if (not.getNextAlertTime() == null || not.getNextAlertTime().compareTo(now) >= 0) {
						break;
					}
					not.getNotification().setSnoozeCount(not.getNotification().getSnoozeCount() + 1);
					not.getNotification().setSnoozeTime(now.plus(Duration.ofMinutes(5)));
					not.refresh();
				}
			} finally {
				theNotificationCallbackLock = false;
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
	public ObservableCollection<ActiveNotification> getNotifications() {
		return theNotifications;
	}

	/** @return All notifications for all notes that have a future alert time or have yet to be dismissed, sorted soonest to farthest */
	public ObservableSortedCollection<ActiveNotification> getActiveNotifications() {
		return theActiveNotifications;
	}

	/** @return The config for UI settings */
	public ObservableConfig getConfig() {
		return theConfig;
	}

	private Set<ActiveNotification> theCurrentNotifications = new HashSet<>();
	private Instant theLastNotification;

	private void processNotifications() {
		if (theNotificationCallbackLock) {
			return;
		}
		theNotificationCallbackLock = true;
		try {
			Instant now = Instant.now();
			Instant nextAlert = null;
			Iterator<ActiveNotification> cnIter = theCurrentNotifications.iterator();
			while (cnIter.hasNext()) {
				ActiveNotification cn = cnIter.next();
				if (!cn.theElement.isPresent()) {
					cnIter.remove();
				} else if (cn.getNextAlertTime() == null || cn.getNextAlertTime().compareTo(now) > 0) {
					theNotifications.mutableElement(cn.theElement).set(cn);
					cnIter.remove();
				}
			}
			boolean reAlert = !theCurrentNotifications.isEmpty() //
					&& (theLastNotification == null || now.compareTo(theLastNotification.plus(theReNotifyDuration)) > 0);
			Instant reNotify = theLastNotification == null ? now.plus(theReNotifyDuration) : theLastNotification.plus(theReNotifyDuration);
			List<ActiveNotification> currentNotifications = new LinkedList<>();
			for (CollectionElement<ActiveNotification> notification : theActiveNotifications.elements()) {
				if (notification.get().getNextAlertTime().compareTo(now) > 0) {
					nextAlert = notification.get().getNextAlertTime();
					break;
				}
				currentNotifications.add(notification.get());
				if (theCurrentNotifications.add(notification.get())) {
					reAlert = true;
					theNotifications.mutableElement(notification.get().theElement).set(notification.get());
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
					String msg = notification.getNotification().getName();
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
					theLastNotification = now;
					theTrayIcon.displayMessage(notification.getNotification().getNote().getName(), msg, MessageType.INFO);
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
							msg.append(not.getNotification().getName());
						}
					}
					theLastNotification = now;
					theTrayIcon.displayMessage(currentNotifications.size() + " Notifications", msg.toString(), MessageType.INFO);
				}
			}

			if (!currentNotifications.isEmpty()) {
				if (nextAlert == null || nextAlert.compareTo(reNotify) > 0) {
					nextAlert=reNotify;
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
				SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS");
				System.out.println("Next alert at " + format.format(Date.from(nextAlert)) + " ("
						+ QommonsUtils.printTimeLength(nextAlert.toEpochMilli() - System.currentTimeMillis())
						+ ")");
				theAlertTask.runNextAt(nextAlert);
			} else {
				System.out.println("No next alert");
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
								.modifyEditor(tf2 -> tf2.setEmptyText("Search...").setCommitOnType(true)))//
				.addTable(theSubjects.getValues(), table -> {
					table.fill().fillV()//
							.withFiltering(filter)//
							.withItemName("Subject")//
							.withNameColumn(Subject::getName, null, true, col -> col.withWidths(50, 120, 300))//
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
							/*.withAdd(() -> {
								// Don't select the subject--it doesn't have anything in the timeline, so just edit it here inline
								return theSubjects.create()//
										.with(Subject::getName,
												StringUtils.getNewItemName(theSubjects.getValues(), Subject::getName, "New Subject",
														StringUtils.SIMPLE_DUPLICATES))//
										.create().get();
							}, null)//*/
							/*.withRemove(subjects -> {
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
										theNotes.getValues().removeAll(subject.getReferences());
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
							}, mod -> mod.confirmForItems("Confirm Subject Deletion", "Permanently delete", "?", true))//*/
					;
				});
	}

	private void populateNotesTab(PanelPopulator<?, ?> notesTab) {
		SettableValue<TableContentControl> filter = SettableValue.build(TableContentControl.class).safe(false)
				.withValue(TableContentControl.DEFAULT).build();
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
																}*/, false, col -> col.withWidths(50, 120, 300))//
							.withColumn("Occurred", Instant.class, Note::getOccurred,
									col -> col/*.withMutation(mut -> mut.mutateAttribute((note, occurred) -> {
												note.setOccurred(occurred);
												note.setModified(Instant.now());
												}).asText(DATE_FORMAT))*/.withWidths(100, 150, 300))//
							.withColumn("Content", String.class, Note::getContent, null)//
							.withColumn("References", String.class, //
									note -> StringUtils.print(", ", note.getReferences(), String::valueOf).toString(), null)//
							.withColumn("Next Alert Time", Instant.class, note -> {
								Instant soonest = null;
								for (ActiveNotification notification : theNotificationsById.get(note.getId())) {
									if (soonest == null || (notification.getNextAlertTime() != null
											&& notification.getNextAlertTime().compareTo(soonest) < 0)) {
										soonest = notification.getNextAlertTime();
									}
								}
								return soonest;
							}, col -> col.withWidths(100, 150, 300)
									.formatText(t -> t == null ? ""
											: QommonsUtils.printRelativeTime(t.toEpochMilli(), System.currentTimeMillis(),
													QommonsUtils.TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)))//
							.withColumn("Noted", Instant.class, Note::getNoted,
									col -> col.withWidths(100, 150, 300)
											.formatText(t -> QommonsUtils.printRelativeTime(t.toEpochMilli(), System.currentTimeMillis(),
													QommonsUtils.TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)))//
							.withColumn("Modified", Instant.class, Note::getNoted,
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
										.with(Note::getNoted, now)//
										.with(Note::getOccurred, now)//
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
		SettableValue<TableContentControl> filter = SettableValue.build(TableContentControl.class).safe(false)
				.withValue(TableContentControl.DEFAULT).build();
		notificationsTab//
				.addTextField(null, filter, TableContentControl.FORMAT,
						tf -> tf.fill().withTooltip(TableContentControl.TABLE_CONTROL_TOOLTIP)
								.modifyEditor(tf2 -> tf2.setEmptyText("Search...").setCommitOnType(true)))//
				.addTable(theActiveNotifications, table -> {
					table.fill().fillV()//
							.withFiltering(filter)//
							.withItemName("Notification")//
							.withSelection(theSelectedNotification, false)//
							.withColumn("Active", boolean.class, n -> n.getNotification().isActive(),
									col -> col.withWidths(10, 40, 50).withMutation(mut -> mut.mutateAttribute((n, a) -> {
										theNotificationCallbackLock = true;
										try {
											n.getNotification().setActive(a);
											n.refresh();
										} finally {
											theNotificationCallbackLock = true;
										}
										theNotifications.mutableElement(n.theElement).set(n);
									}).asCheck()))//
							.withColumn("Note", String.class, not -> not.getNotification().getNote().getName(),
									col -> col.withWidths(100, 200, 500))//
					;
					populateNotificationName(table, notificationsTab.getContainer(), false);
					populateNextAlertTime(table, notificationsTab.getContainer(), false);
					populateRecurrence(table, notificationsTab.getContainer(), false);
					populateNotificationActions(table, notificationsTab.getContainer(), hasSnoozeAll);
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

	private static void populateNotificationName(PanelPopulation.TableBuilder<ActiveNotification, ?> table, Container container,
			boolean mutable) {
		table//
				.withNameColumn(n -> n.getNotification().getName(), mutable ? (not, name) -> {
					not.getNotification().setName(name);
					not.getNotification().getNote().setModified(Instant.now());
				} : null, false, col -> col.withWidths(100, 200, 500));
	}

	private void populateNextAlertTime(PanelPopulation.TableBuilder<ActiveNotification, ?> table, Container container, boolean mutable) {
		table.withColumn("Next Alert", Instant.class, ActiveNotification::getNextAlertTime,
				col -> {
					col.withWidths(100, 150, 300).decorate((cell, deco) -> {
						Instant next = cell.getModelValue().getNextAlertTime();
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
					});
					if (mutable) {
						col.withMutation(mut -> {
							mut.mutateAttribute((not, time) -> {
								theNotificationCallbackLock = true;
								try {
									if (!not.getNotification().isActive()) {
										if (table.alert("Set Active", "This notification is not active.\nActivate it?").confirm(false)) {
											not.getNotification().setActive(true);
										}
									}
									if (not.getNotification().getEndTime() != null
											&& time.compareTo(not.getNotification().getEndTime()) >= 0) {
										if (table.alert("Clear End Time?",
												"The given next alert time ("
														+ QommonsUtils.printRelativeTime(time.toEpochMilli(), System.currentTimeMillis(),
																QommonsUtils.TimePrecision.MINUTES, TimeZone.getDefault(), 0, null)
														+ ") is greater than the current end time for the notification ("
														+ QommonsUtils.printRelativeTime(not.getNotification().getEndTime().toEpochMilli(),
																System.currentTimeMillis(), QommonsUtils.TimePrecision.MINUTES,
																TimeZone.getDefault(), 0, null)
														+ ")."//
														+ "\nDo you want to clear the end time for this notification?")
												.confirm(false)) {
											not.getNotification().setEndTime(null);
										} else {
											return;
										}
									}
									not.getNotification().setSnoozeCount(0);
									not.getNotification().setSnoozeTime(null);
								} finally {
									theNotificationCallbackLock = false;
								}
								not.getNotification().setInitialTime(time);
							}).asText(FUTURE_DATE_FORMAT);
						});
					} else {
						col.formatText(t -> t == null ? ""
								: QommonsUtils.printRelativeTime(t.toEpochMilli(), System.currentTimeMillis(),
										QommonsUtils.TimePrecision.MINUTES, TimeZone.getDefault(), 0, null));
					}
				});
	}

	private void populateRecurrence(PanelPopulation.TableBuilder<ActiveNotification, ?> table, Container container, boolean mutable) {
		table.withColumn("Recurrence", String.class,
				n -> n.getNotification().getRecurInterval() == null ? "" : n.getNotification().getRecurInterval(), col -> {
			if (mutable) {
				col.withMutation(mut -> mut.mutateAttribute((not, interval) -> {
					not.getNotification().setRecurInterval(interval);
					not.getNotification().getNote().setModified(Instant.now());
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

	private void populateNotificationActions(PanelPopulation.TableBuilder<ActiveNotification, ?> table, Container container,
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
							not.getNotification().setSnoozeCount(0);
							not.getNotification().setLastAlertTime(now);
						} else if (action.equals("Skip Next")) {
							theNotificationCallbackLock = true;
							try {
								Instant next, preNext, preLast;
								preLast = not.getNotification().getLastAlertTime();
								next = preNext = not.getNextAlertTime();
								if (next == null) {
									return;
								}
								Instant preOrig = not.getNotification().getInitialTime();
								not.getNotification().setInitialTime(next);
								not.getNotification().setLastAlertTime(next);
								not.refresh();
								next = not.getNextAlertTime();
								if (next == null) { // ??
									not.getNotification().setInitialTime(preOrig);
									return;
								}
								next = not.getNextAlertTime();
								not.getNotification().setLastAlertTime(preLast); // Restore
								not.getNotification().setInitialTime(preOrig); // Restore
								not.refresh();
								if (next == null) {// ??
									return;
								}
								CellEditor editor = table.getEditor().getCellEditor();
								if (editor != null) {
									editor.cancelCellEditing();
								}
								if (table
										.alert("Skip Next Alert?", "Skip the next alert for " + not.getNotification().getName() + " at "
												+ QommonsUtils.printRelativeTime(preNext.toEpochMilli(), now.toEpochMilli(),
														TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)
												+ " (" + QommonsUtils.printDuration(TimeUtils.between(now, preNext), false)
												+ " from now)?\n"//
												+ "The next alert would then be at "
												+ QommonsUtils.printRelativeTime(next.toEpochMilli(), now.toEpochMilli(),
														TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)
												+ " (" + QommonsUtils.printDuration(TimeUtils.between(now, next), false) + " from now).")
										.confirm(true)) {
									not.getNotification().setInitialTime(next);
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
					}).editableIf((not, __) -> {
						if (not.getNextAlertTime() == null) {
							return false;
						} else if (not.getNextAlertTime().compareTo(Instant.now()) > 0
								&& not.getNotification().getRecurInterval() == null) {
							return false;
						} else {
							return true;
						}
					}).asCombo(s -> s, (not, __) -> {
						if (not.getModelValue().getNextAlertTime().compareTo(Instant.now()) <= 0) {
							return ObservableCollection.of(String.class, "Dismiss", //
									"Snooze 5 min", "Snooze 30 min", "Snooze 1 hour", "Snooze For...", "Snooze Until...");
						} else if (not.getModelValue().getNotification().getSnoozeTime() != null
								&& not.getModelValue().getNotification().getSnoozeTime().compareTo(Instant.now()) > 0) {
							return ObservableCollection.of(String.class, "Dismiss");
						} else if (not.getModelValue().getNextAlertTime() != null
								&& not.getModelValue().getNotification().getRecurInterval() != null) {
							return ObservableCollection.of(String.class, "Skip Next");
						} else {
							return ObservableCollection.of(String.class);
						}
					}).clicks(1);
				}));
	}

	private void populateSubjectEditor(PanelPopulator<?, ?> panel, Subject value) {
		ObservableSortedCollection<Note> references = value.getReferences().flow().sorted((n1, n2) -> {
			int comp = n1.getOccurred().compareTo(n2.getOccurred());
			if (comp == 0) {
				comp = n1.getModified().compareTo(n2.getModified());
			}
			return -comp;
		}).collect();

		SettableValue<TableContentControl> filter = SettableValue.build(TableContentControl.class).safe(false)
				.withValue(TableContentControl.DEFAULT).build();
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
							.withColumn("Occurred", Instant.class, Note::getOccurred, col -> col.withWidths(100, 150, 300))//
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
					// TODO
					;
				});
	}

	private void populateNoteEditor(PanelPopulator<?, ?> panel, Note value) {
		ObservableSortedCollection<ActiveNotification> activeNots = theNotifications.flow()//
				.filter(n -> n.getNotification().getNote() == value ? null : "Not in the selected Note")//
				.sorted((n1, n2) -> {
					Instant next1 = n1.getNextAlertTime();
					Instant next2 = n2.getNextAlertTime();
					if (next1 == null) {
						if (next2 == null) {
							return 0;
						} else {
							return 1;
						}
					} else if (next2 == null) {
						return -1;
					} else {
						return next1.compareTo(next2);
					}
				}).collect();
		panel.addTextField("Name", EntityReflector.observeField(value, Note::getName)//
				.filterAccept(n -> {
					if (n.length() == 0) {
						return "Note name cannot be empty";
					}
					return null;
				}), SpinnerFormat.NUMERICAL_TEXT, tf -> tf.fill())//
				.addTextField("Occurred", EntityReflector.observeField(value, Note::getOccurred), PAST_DATE_FORMAT, tf -> tf.fill())//
				.addTextArea(null, EntityReflector.observeField(value, Note::getContent), Format.TEXT,
						tf -> tf.fill().fillV().modifyEditor(ta -> ta.withRows(8).setSelectAllOnFocus(false)))//
				.addTable(activeNots, notifications -> {
					notifications.fill().withItemName("Notification")//
							.withColumn("Active", boolean.class, n -> n.getNotification().isActive(),
									col -> col.withWidths(10, 40, 50).withMutation(mut -> mut.mutateAttribute((n, a) -> {
										if (a) {
											Instant now = Instant.now();
											if (n.getNotification().getRecurInterval() == null
													&& n.getNotification().getInitialTime().compareTo(now) < 0) {
												if (notifications
														.alert("Reset Start Time?",
																"The notification's start time time (" + QommonsUtils.printRelativeTime(
																		n.getNotification().getInitialTime().toEpochMilli(),
																		System.currentTimeMillis(), QommonsUtils.TimePrecision.MINUTES,
																		TimeZone.getDefault(), 0, null) + ") has passed."//
																		+ "\nDo you want to reset the start time for this notification?")
														.confirm(false)) {
													// Get "now" again because it may have been a while
													n.getNotification().setInitialTime(Instant.now().plus(Duration.ofMinutes(5)));
												} else {
													return;
												}
											}
											if (n.getNotification().getEndTime() != null
													&& now.compareTo(n.getNotification().getEndTime()) >= 0) {
												if (notifications
														.alert("Clear End Time?",
																"The notification's end time time (" + QommonsUtils.printRelativeTime(
																		n.getNotification().getEndTime().toEpochMilli(),
																		System.currentTimeMillis(), QommonsUtils.TimePrecision.MINUTES,
																		TimeZone.getDefault(), 0, null) + ") has passed."//
																		+ "\nDo you want to clear the end time for this notification?")
														.confirm(false)) {
													n.getNotification().setEndTime(null);
												} else {
													return;
												}
											}
										}
										n.getNotification().setActive(a);
									}).asCheck()));
					populateNotificationName(notifications, panel.getContainer(), true);
					populateNextAlertTime(notifications, panel.getContainer(), true);
					populateRecurrence(notifications, panel.getContainer(), true);
					notifications.withColumn("End", Instant.class, n -> n.getNotification().getEndTime(),
							col -> col.withWidths(100, 150, 300).withMutation(mut -> mut.mutateAttribute((not, end) -> {
								not.getNotification().setEndTime(end);
								not.getNotification().getNote().setModified(Instant.now());
							}).asText(FUTURE_DATE_FORMAT)));
					populateNotificationActions(notifications, panel.getContainer(), true);
					notifications.withColumn("Last Alert", Instant.class, n -> n.getNotification().getLastAlertTime(),
							col -> col.withWidths(100, 150, 300)
									.formatText(t -> t == null ? "Never"
											: QommonsUtils.printRelativeTime(t.toEpochMilli(), System.currentTimeMillis(),
													QommonsUtils.TimePrecision.SECONDS, TimeZone.getDefault(), 0, null)));
					// populateNotificationTable(notifications, panel.getContainer(), true);
					notifications.withAdaptiveHeight(2, 4, 10)//
							.withAdd(() -> {
								Calendar time = Calendar.getInstance();
								time.set(Calendar.SECOND, 0);
								time.set(Calendar.MILLISECOND, 0);
								time.add(Calendar.MINUTE, 5);
								Notification newNot = value.getNotifications().create()//
										.with(Notification::getName,
												StringUtils.getNewItemName(value.getNotifications().getValues(), Notification::getName,
														"Reminder", StringUtils.SIMPLE_DUPLICATES))//
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
							.withRemove(nots -> value.getNotifications().getValues()
									.removeAll(QommonsUtils.map(nots, n -> n.getNotification(), false)), null)//
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
		note.getReferences().remove(reference);
		// TODO Modify the note's content to remove coded reference, replacing with "@Subject" text
	}

	/**
	 * Main method for HypNotiQ
	 * 
	 * @param args
	 *            Command-line arguments, ignored
	 */
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
				.withVisible(SettableValue.build(boolean.class).safe(false).withValue(false).build())// Not shown initially
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
									SimpleObservable<Void> built = SimpleObservable.build().safe(false).build();
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
					.buildReferenceFormat(__ -> subjects.get().getValues(), null)
					.withRetrieverReady(() -> subjects.get() != null)//
					.withField("id", Subject::getId, ObservableConfigFormat.LONG).build();
			noteFormat.withFieldFormat(Note::getReferences, ObservableConfigFormat.ofCollection(//
					TypeTokens.get().keyFor(List.class).<List<Subject>> parameterized(Subject.class), subjectRefFormat, "references",
					"subject"));
		}).withBuiltNotifier(built).buildEntitySet(notes);
	}

	/** Tracks the next (or current) alert time for each notification */
	public static class ActiveNotification implements Comparable<ActiveNotification> {
		private final Notification theNotification;
		ElementId theElement;
		private Instant theNextAlertTime;

		ActiveNotification(Notification notification) {
			theNotification = notification;
			theNextAlertTime = getNextAlertTime(notification);
		}

		/** @return The notification backing this object */
		public Notification getNotification() {
			return theNotification;
		}

		/** @return The next (or current) alert time for this notification, or null if no further notifications are scheduled */
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
			if (recur == null || recur.isEmpty()) {
				if (start.compareTo(lastAlert) > 0) {
					return start;
				} else {
					return null;
				}
			}
			char lastChar = recur.charAt(recur.length() - 1);
			int day, number;
			switch (lastChar) {
			case '-': // Code for days from the last of the month
				recur = recur.substring(0, recur.length() - 1);
				Calendar cal = TimeUtils.CALENDAR.get();
				cal.setTimeZone(TimeZone.getDefault());
				cal.setTimeInMillis(start.toEpochMilli());
				day = cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.get(Calendar.DAY_OF_MONTH);
				number = -1;
				break;
			case '#': // Code for Xth [weekday] of the month
				recur = recur.substring(0, recur.length() - 1);
				cal = TimeUtils.CALENDAR.get();
				cal.setTimeZone(TimeZone.getDefault());
				cal.setTimeInMillis(start.toEpochMilli());
				day = cal.get(Calendar.DAY_OF_WEEK);
				number = cal.get(Calendar.WEEK_OF_MONTH) - 1;
				break;
			default: // Normal frequency
				day = number = -1;
			}

			ParsedDuration duration;
			try {
				duration = TimeUtils.parseDuration(recur);
			} catch (ParseException e) {
				e.printStackTrace();
				return null;
			}
			Instant nextAlert;
			if (lastAlert.compareTo(start) < 0) {
				nextAlert = start;
			} else {
				Duration estDuration = duration.asDuration();
				int times = TimeUtils.divide(Duration.between(start, lastAlert), estDuration);
				if (times <= 0) {
					nextAlert = nextAlert(start, duration, day, number);
				} else {
					if (times > 1) {
						nextAlert = duration.times(times - 2).addTo(start, TimeZone.getDefault());
					} else {
						nextAlert = start;
					}
					do {
						nextAlert = nextAlert(nextAlert, duration, day, number);
					} while (nextAlert.compareTo(lastAlert) < 0);
				}
			}
			if (notification.getEndTime() != null && nextAlert.compareTo(notification.getEndTime()) > 0) {
				nextAlert = null;
			}
			return nextAlert;
		}

		private static Instant nextAlert(Instant start, ParsedDuration duration, int day, int number) {
			if (number >= 0) { // Xth [weekday] of the month
				Calendar cal = TimeUtils.CALENDAR.get();
				cal.setTimeInMillis(start.toEpochMilli());
				cal.set(Calendar.DAY_OF_MONTH, 1);
				cal.add(Calendar.MONTH, 1);
				cal.set(Calendar.DAY_OF_WEEK, day); // TODO Moves forward, right?
				cal.add(Calendar.DAY_OF_MONTH, number * 7);
				return Instant.ofEpochMilli(cal.getTimeInMillis());
			} else if (day >= 0) {// X days before the end of the month
				Calendar cal = TimeUtils.CALENDAR.get();
				cal.setTimeInMillis(start.toEpochMilli());
				cal.set(Calendar.DAY_OF_MONTH, 1);
				cal.add(Calendar.MONTH, 1);
				cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH) - day);
				return Instant.ofEpochMilli(cal.getTimeInMillis());
			} else {
				return duration.addTo(start, TimeZone.getDefault());
			}
		}

		@Override
		public String toString() {
			return theNotification.toString();
		}
	}
}
