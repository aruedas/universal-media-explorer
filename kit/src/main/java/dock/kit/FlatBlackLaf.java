package dock.kit;

import com.formdev.flatlaf.FlatDarkLaf;

/**
 * The "Black" theme — near-true-black surfaces matching SimpleCommander's
 * DARK palette. Extends FlatDarkLaf so every dark-mode default carries over;
 * the properties file overrides only the surface/card/separator colors.
 */
public class FlatBlackLaf extends FlatDarkLaf {

    public static final String NAME = "Black";

    @Override
    public String getName() { return NAME; }

    @Override
    public String getDescription() { return "Black (SimpleCommander-style AMOLED dark)"; }
}
