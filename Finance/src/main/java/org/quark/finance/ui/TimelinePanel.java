package org.quark.finance.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.JPanel;

import org.observe.Observable;
import org.observe.ObservableValue;
import org.observe.collect.ObservableCollection;
import org.observe.expresso.ModelTypes;
import org.qommons.ArrayUtils;
import org.qommons.Colors;
import org.qommons.LongList;
import org.qommons.TimeUtils;
import org.quark.finance.entities.Plan;
import org.quark.finance.entities.PlanComponent;
import org.quark.finance.entities.PlanVariable;
import org.quark.finance.entities.PlanVariableType;
import org.quark.finance.logic.Money;
import org.quark.finance.logic.PlanSimulation;
import org.quark.finance.logic.PlanSimulation.SimulationResults;

public class TimelinePanel extends JPanel {
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM dd, yyyy");

	private final ObservableCollection<PlanItem> theItems;
	private final ObservableValue<Instant> theStart;
	private final ObservableValue<Instant> theEnd;
	private final boolean isStacked;

	public TimelinePanel(ObservableCollection<PlanItem> items, ObservableValue<Instant> start, ObservableValue<Instant> end,
		boolean stacked) {
		theItems = items;
		theStart = start;
		theEnd = end;
		isStacked = stacked;

		Observable.or(items.simpleChanges(), start.noInitChanges(), end.noInitChanges()).act(__ -> repaint());
		addMouseMotionListener(new MouseMotionListener() {
			@Override
			public void mouseMoved(MouseEvent e) {
				Instant startV = theStart.get();
				Instant endV = theEnd.get();
				if (startV == null || endV == null) {
					return;
				}
				Instant time = startV.plus(TimeUtils.multiply(TimeUtils.between(startV, endV), e.getX() * 1.0 / getWidth()));
				StringBuilder tooltip = new StringBuilder("<html>").append(DATE_FORMAT.format(Date.from(time)));
				Set<SimulationResults> simResults = new LinkedHashSet<>();
				long sum = 0;
				for (PlanItem item : theItems) {
					simResults.add(item.results);
					if (item.results.getModels() == null || !item.results.finished.get()) {
						continue;
					}
					int frame;
					if (item.values.length == 0 || item.component.getError() != null) {
						continue;
					}
					frame = Math.round(e.getX() * 1.0f / getWidth() * item.values.length);
					if (item.values[frame] == 0) {
						continue;
					}
					PlanComponent pc = item.contributor != null ? item.contributor : item.component;
					Color color = pc.getColor();
					if (color == null) {
						color = Color.black;
					}
					tooltip.append("<br /><font color=\"").append(Colors.toHTML(color)).append("\">")//
						.append(pc.getName()).append(": ").append(new Money(item.values[frame]))//
						.append("</font>");
					sum += item.values[frame];
				}
				tooltip.append("<br />Total: ").append(new Money(sum));

				for (SimulationResults results : simResults) {
					if (results.getModels() == null) {
						continue;
					}
					double length = TimeUtils.toSeconds(TimeUtils.between(startV, endV));
					for (PlanVariable vbl : results.plan.getVariables().getValues()) {
						if (!vbl.isShown() || vbl.getError() != null || vbl.getVariableType() != PlanVariableType.Instant) {
							continue;
						}
						Instant vblTime = (Instant) results.getModels().get(vbl.getName(), ModelTypes.Value.any()).get();
						int pos = (int) Math.round(getWidth() * TimeUtils.toSeconds(TimeUtils.between(startV, vblTime)) / length);
						if (Math.abs(e.getX() - pos) <= 1) {
							Color color = vbl.getColor();
							if (color == null) {
								color = Color.black;
							}
							tooltip.append("<br /><font color=\"" + Colors.toHTML(color) + "\">")//
								.append(vbl.getName())//
								.append("</font>");
						}
					}
				}
				setToolTipText(tooltip.toString());
			}

			@Override
			public void mouseDragged(MouseEvent e) {}
		});
	}

	@Override
	protected void paintComponent(Graphics g) {
		g.setColor(Color.white);
		g.fillRect(0, 0, getWidth(), getHeight());

		Instant start = theStart.get();
		Instant end = theEnd.get();
		if (start == null || end == null) {
			return;
		}

		List<PlanItem> items = new ArrayList<>(theItems);
		if (items.isEmpty()) {
			return;
		}

		if (isStacked) {
			renderStacked((Graphics2D) g, items, start, end);
		} else {
			renderIndependent((Graphics2D) g, items, start, end);
		}
	}

	private void renderStacked(Graphics2D g, List<PlanItem> items, Instant start, Instant end) {
		int frames = items.get(0).values.length;
		// Figure out the bounds
		long min = 0, max = 0;
		for (int frame = 0; frame < frames; frame++) {
			long sumPos = 0, sumNeg = 0;
			for (PlanItem item : items) {
				if (item.values[frame] >= 0) {
					sumPos += item.values[frame];
				} else {
					sumNeg += item.values[frame];
				}
			}
			if (sumPos + sumNeg >= 0) {
				if (sumPos > max) {
					max = sumPos;
				}
			} else if (sumNeg < min) {
				min = sumNeg;
			}
		}
		if (min > 0) {
			min = 0;
		} else if (min >= max) {
			return;
		}

		// Draw
		int left = getInsets().left, top = getInsets().top;
		int w = getWidth() - left - getInsets().right;
		int h = getHeight() - top - getInsets().bottom;
		int zeroY = (int) Math.round(h * max * 1.0 / (max - min));
		List<PlanItem> positive = new ArrayList<>(), negative = new ArrayList<>();
		LongList posVals = new LongList(), negVals = new LongList();
		int lastTotalY = -1;
		for (int x = 0; x < w; x++) {
			int frame = Math.min(Math.round(x * 1.0f / w * frames), frames - 1);
			// Sort the items into positive and negative contributions
			positive.clear();
			negative.clear();
			posVals.clear();
			negVals.clear();
			long sumPos = 0, sumNeg = 0;
			for (PlanItem item : items) {
				if (item.values[frame] > 0) {
					positive.add(item);
					sumPos += item.values[frame];
					posVals.add(sumPos);
				} else if (item.values[frame] < 0) {
					negative.add(item);
					sumNeg += item.values[frame];
					negVals.add(sumNeg);
				}
			}

			if (sumPos + sumNeg >= 0) {
				PlanItem lastNeg = null;
				int negIdx = negative.size();
				int fromY = zeroY;
				for (int p = 0; p < positive.size(); p++) {
					PlanItem pos = positive.get(p);
					long nextPos = posVals.get(p);
					while (negIdx > 0 && sumPos + negVals.get(negIdx - 1) < nextPos) {
						int toY = getY(sumPos + negVals.get(negIdx - 1), min, max, h);
						if (lastNeg == null) {
							drawOverlapping(g, Arrays.asList(pos), x, fromY, toY);
						} else {
							drawOverlapping(g, Arrays.asList(pos, lastNeg), x, fromY, toY);
						}
						negIdx--;
						lastNeg = negative.get(negIdx);
						fromY = toY;
					}
					int toY = getY(nextPos, min, max, h);
					if (lastNeg == null) {
						drawOverlapping(g, Arrays.asList(pos), x, fromY, toY);
					} else {
						drawOverlapping(g, Arrays.asList(pos, lastNeg), x, fromY, toY);
					}
					fromY = toY;
				}
			} else {
				PlanItem lastPos = null;
				int posIdx = positive.size();
				int fromY = zeroY;
				for (int negIdx = 0; negIdx < negative.size(); negIdx++) {
					PlanItem neg = negative.get(negIdx);
					long nextNeg = negVals.get(negIdx);
					while (posIdx > 0 && sumNeg + posVals.get(posIdx - 1) > nextNeg) {
						int toY = getY(sumNeg + posVals.get(posIdx - 1), min, max, h);
						if (lastPos == null) {
							drawOverlapping(g, Arrays.asList(neg), x, toY, fromY);
						} else {
							drawOverlapping(g, Arrays.asList(lastPos, neg), x, toY, fromY);
						}
						posIdx--;
						lastPos = positive.get(posIdx);
						fromY = toY;
					}
					int toY = getY(nextNeg, min, max, h);
					if (lastPos == null) {
						drawOverlapping(g, Arrays.asList(neg), x, toY, fromY);
					} else {
						drawOverlapping(g, Arrays.asList(lastPos, neg), x, toY, fromY);
					}
					fromY = toY;
				}
			}
			int totalY = getY(sumPos + sumNeg, min, max, h);
			if (x > 0) {
				g.setColor(Color.black);
				g.drawLine(left + x - 1, top + lastTotalY, left + x, top + totalY);
			}
			lastTotalY = totalY;
		}

		paintTimes(g, items, start, end, zeroY);
	}

	private void renderIndependent(Graphics2D g, List<PlanItem> items, Instant start, Instant end) {
		// Figure out the bounds
		long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
		for (PlanItem item : items) {
			for (long value : item.values) {
				if (value < min) {
					min = value;
				}
				if (value > max) {
					max = value;
				}
			}
		}
		if (min > 0) {
			min = 0;
		} else if (min >= max) {
			return;
		}

		// Draw
		int left = getInsets().left, top = getInsets().top;
		int w = getWidth() - left - getInsets().right;
		int h = getHeight() - top - getInsets().bottom;
		int zeroY = (int) Math.round(h * max * 1.0 / (max - min));
		int frames = items.get(0).values.length;
		for (int x = 0; x < w; x++) {
			// Sort the items by current balance/amount
			int frame = Math.min(Math.round(x * 1.0f / w * frames), frames - 1);
			Collections.sort(items, (i1, i2) -> Long.compare(i1.values[frame], i2.values[frame]));
			int zeroIndex = ArrayUtils.binarySearch(items, item -> Long.compare(0L, item.values[frame]));
			if (zeroIndex == -1) {
				zeroIndex = 0;
			} else if (zeroIndex < 0) {
				zeroIndex = -zeroIndex - 1;
			}
			int fromY = zeroY;
			for (int i = zeroIndex; i > 0; i--) {
				List<PlanItem> subItems = items.subList(0, i);
				int toY = getY(subItems.get(i - 1).values[frame], min, max, h);
				drawOverlapping(g, subItems, x, toY, fromY);
				fromY = toY;
			}
			fromY = zeroY;
			for (int i = zeroIndex; i < items.size(); i++) {
				List<PlanItem> subItems = items.subList(i, items.size());
				int toY = getY(items.get(i).values[frame], min, max, h);
				drawOverlapping(g, subItems, x, fromY, toY);
				fromY = toY;
			}
		}

		paintTimes(g, items, start, end, zeroY);
	}

	private static int getY(long value, long min, long max, int h) {
		return h - Math.round((value - min) * 1.0f * h / (max - min));
	}

	private void drawOverlapping(Graphics2D g, List<PlanItem> subItems, int x, int fromY, int toY) {
		if (fromY > toY) {
			int temp = fromY;
			fromY = toY;
			toY = temp;
		}
		for (int y = fromY; y < toY; y++) {
			int itemIdx = (x / 3 + y / 3) % subItems.size();
			if (itemIdx < 0) {
				itemIdx += subItems.size();
			}
			PlanItem item = subItems.get(itemIdx);
			Plan plan = item.component.getPlan();
			Color color = item.contributor != null ? item.contributor.getColor() : item.component.getColor();
			if (color == null) {
				color = Color.black;
			}
			if (plan.getStippleDotLength() < plan.getStippleLength()) {
				boolean stipple = ((x + y) % plan.getStippleLength() + 1) < plan.getStippleDotLength();
				if (!stipple) {
					g.setColor(Color.black);
				} else {
					g.setColor(color);
				}
			} else {
				g.setColor(color);
			}
			g.drawRect(getInsets().left + x, getInsets().top + y, 1, 1);
		}
	}

	private void paintTimes(Graphics2D g, List<PlanItem> items, Instant start, Instant end, int zeroY) {
		int left = getInsets().left, top = getInsets().top;
		int w = getWidth() - left - getInsets().right;
		int h = getHeight() - top - getInsets().bottom;

		Set<SimulationResults> simResults = items.stream().map(item -> item.results).collect(Collectors.toSet());
		double length = TimeUtils.toSeconds(TimeUtils.between(start, end));
		for (PlanSimulation.SimulationResults results : simResults) {
			if (results.getModels() == null || !results.finished.get()) {
				continue;
			}
			for (PlanVariable vbl : results.plan.getVariables().getValues()) {
				if (!vbl.isShown() || vbl.getError() != null || vbl.getVariableType() != PlanVariableType.Instant) {
					continue;
				}
				Instant vblTime = (Instant) results.getModels().get(vbl.getName(), ModelTypes.Value.any()).get();
				int pos = (int) Math.round(w * TimeUtils.toSeconds(TimeUtils.between(start, vblTime)) / length);
				if (pos >= 0 && pos < w) {
					g.setColor(vbl.getColor());
					g.fillRect(left + pos - 1, top, 2, h);
				}
			}
		}
		if (zeroY > 0 && zeroY < h) {
			g.setColor(Color.gray);
			g.setStroke(new BasicStroke(2));
			g.drawLine(left, top + zeroY, w, top + zeroY);
		}
	}
}
