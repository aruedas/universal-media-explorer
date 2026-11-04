import dock.kit.WrapLayout;
import java.awt.Dimension;
import javax.swing.JButton;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract the toolkit row depends on: when the window narrows, the row
 * gets taller. Plain FlowLayout keeps reporting a one-row height and the
 * buttons that wrapped are clipped out of sight — this is why WrapLayout
 * exists, so both halves are pinned here.
 */
class WrapLayoutTest {

    private static final int BUTTON_W = 100;
    private static final int BUTTON_H = 30;

    /** Six fixed-size buttons in a row {@code width} px wide. */
    private static JPanel row(int width, boolean wrap) {
        JPanel panel = new JPanel(wrap ? new WrapLayout()
                : new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 4));
        for (int i = 0; i < 6; i++) {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(BUTTON_W, BUTTON_H));
            panel.add(b);
        }
        panel.setSize(width, 120);
        return panel;
    }

    private static int height(int width, boolean wrap) {
        return (int) row(width, wrap).getPreferredSize().getHeight();
    }

    @Test
    void aWideRowStaysOneRowTall() {
        assertEquals(BUTTON_H, height(2000, true) - 2 * 4,
                "one row of buttons plus the layout's own top and bottom gaps");
    }

    @Test
    void aNarrowRowGrowsForTheWrappedTail() {
        int wide = height(2000, true);
        int narrow = height(300, true);
        assertTrue(narrow > wide, "narrow " + narrow + " must exceed wide " + wide);
        assertEquals(3 * BUTTON_H + 4 * 4, narrow,
                "two buttons per row: three rows, gaps between and around");
    }

    @Test
    void plainFlowLayoutNeverAdmitsItNeedsMoreHeight() {
        assertEquals(height(2000, false), height(300, false),
                "the bug WrapLayout fixes: FlowLayout's preferred height ignores wrapping");
    }

    @Test
    void theMeasuredWidthIsTheParentSWhenTheRowHasNoneYet() {
        JPanel panel = row(0, true);
        JPanel shell = new JPanel(new java.awt.BorderLayout());
        shell.setSize(300, 400);
        shell.add(panel, java.awt.BorderLayout.NORTH);
        assertTrue(panel.getPreferredSize().getHeight() > BUTTON_H + 2 * 4,
                "a row that has never been laid out still sizes for its parent's width");
    }

    /** The reason a group is one run: a caption that ends a line alone, with
     *  its buttons on the next, labels nothing. */
    @Test
    void aTaggedGroupMovesWholeEvenWhenItOverflows() {
        JPanel panel = new JPanel(new WrapLayout());
        for (int group = 1; group <= 2; group++) {
            for (int i = 0; i < 3; i++) {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(BUTTON_W, BUTTON_H));
                b.putClientProperty(WrapLayout.GROUP, group);
                panel.add(b);
            }
        }
        panel.setSize(250, 120);
        panel.doLayout();
        int firstRow = panel.getComponent(0).getY();
        for (int i = 0; i < 3; i++) {
            assertEquals(firstRow, panel.getComponent(i).getY(),
                    "group 1 stays on one line");
        }
        assertTrue(panel.getComponent(3).getY() > firstRow,
                "group 2 starts the next line, whole");
        assertEquals(panel.getComponent(3).getY(), panel.getComponent(5).getY(),
                "and its own three members share it");
    }
}
