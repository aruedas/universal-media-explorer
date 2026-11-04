package dock.kit;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * A left-aligned flow row that reports the height it will actually take, and
 * that never splits a labelled group across two rows.
 *
 * <p>FlowLayout wraps while laying out but its preferred size always assumes
 * one endless row, so a toolbar docked to the north is given a single row's
 * height and everything that wrapped is clipped outside the window edge. Here
 * the rows are counted first, so a narrow window grows the row instead of
 * hiding its tail.
 *
 * <p>Components tagged with the same {@link #GROUP} client property form one
 * run: a break lands before a run, never inside it. A caption that introduces
 * a group is then always followed by its own buttons on the same line.
 *
 * <p>The width measured for the preferred size is the parent's, not the row's
 * own — a frame resize asks for this size while the row still carries its
 * previous bounds, and measuring the old width is what leaves a wrapped tail
 * clipped after the window shrinks.
 */
public final class WrapLayout extends FlowLayout {

    /** Client property: the run a component belongs to. Same value = same row. */
    public static final String GROUP = "dock.wrap.group";

    public WrapLayout() {
        super(LEFT, Tokens.GAP_1, Tokens.GAP_1);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        Insets in = target.getInsets();
        List<List<Component>> rows = rows(target, measureWidth(target) - in.left - in.right);
        int widest = 0;
        int height = 0;
        for (List<Component> row : rows) {
            widest = Math.max(widest, rowWidth(row));
            height += rowHeight(row);
        }
        return new Dimension(widest + in.left + in.right,
                height + (rows.size() - 1) * getVgap() + in.top + in.bottom + 2 * getVgap());
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        return preferredLayoutSize(target);
    }

    @Override
    public void layoutContainer(Container target) {
        Insets in = target.getInsets();
        int limit = target.getWidth() - in.left - in.right;
        List<List<Component>> rows = rows(target, limit);
        int y = in.top;
        for (List<Component> row : rows) {
            int x = in.left;
            int tall = rowHeight(row);
            for (Component c : row) {
                Dimension d = c.getPreferredSize();
                c.setBounds(x, y + (tall - d.height) / 2, d.width, d.height);
                x += d.width + getHgap();
            }
            y += tall + getVgap();
        }
    }

    /**
     * The visible components split into rows of {@code limit} px or less.
     * A run moves whole: the break happens before it, even when its first
     * members would still have fitted on the line.
     */
    private List<List<Component>> rows(Container target, int limit) {
        List<List<Component>> runs = runs(target);
        List<List<Component>> rows = new ArrayList<>();
        List<Component> row = new ArrayList<>();
        int used = 0;
        for (List<Component> run : runs) {
            int width = rowWidth(run);
            if (!row.isEmpty() && used + getHgap() + width > limit) {
                rows.add(row);
                row = new ArrayList<>();
                used = 0;
            }
            row.addAll(run);
            used += (used > 0 ? getHgap() : 0) + width;
        }
        if (!row.isEmpty() || rows.isEmpty()) rows.add(row);
        return rows;
    }

    /** Components grouped by their {@link #GROUP} tag; untagged ones stand alone. */
    private static List<List<Component>> runs(Container target) {
        List<List<Component>> runs = new ArrayList<>();
        Object current = null;
        for (Component c : target.getComponents()) {
            if (c.getParent() != target || !c.isVisible()) continue;
            Object group = c instanceof javax.swing.JComponent tagged
                    ? tagged.getClientProperty(GROUP) : null;
            if (group == null || !java.util.Objects.equals(group, current) || runs.isEmpty()) {
                runs.add(new ArrayList<>());
            }
            runs.get(runs.size() - 1).add(c);
            current = group;
        }
        return runs;
    }

    private int rowWidth(List<Component> row) {
        int width = 0;
        for (Component c : row) {
            width += (width > 0 ? getHgap() : 0) + c.getPreferredSize().width;
        }
        return width;
    }

    private static int rowHeight(List<Component> row) {
        int height = 0;
        for (Component c : row) height = Math.max(height, c.getPreferredSize().height);
        return height;
    }

    private static int measureWidth(Container target) {
        Container parent = target.getParent();
        return parent != null && parent.getWidth() > 0 ? parent.getWidth() : target.getWidth();
    }
}
