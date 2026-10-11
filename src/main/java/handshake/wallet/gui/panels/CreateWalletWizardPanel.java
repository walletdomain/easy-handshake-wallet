package handshake.wallet.gui.panels;

import handshake.wallet.Bip39;
import handshake.wallet.WalletStorage;
import handshake.wallet.gui.theme.CopyIcon;
import handshake.wallet.gui.theme.WalletButton;
import handshake.wallet.gui.theme.WalletFonts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import handshake.wallet.WalletCrypto;
import handshake.wallet.WalletSession;

/**
 * The "Create a new wallet" flow -- three steps (name, password, seed
 * phrase), a shared Next/Back/Cancel row at the bottom rather than each
 * step managing its own, and the actual wallet creation happening once,
 * at the very end, on "Finish". Nothing is written to storage before
 * that -- cancelling at any point, including after the seed phrase has
 * already been shown, leaves storage untouched.
 */
public class CreateWalletWizardPanel extends JPanel {

    private final WalletStorage storage;
    private final BiConsumer<String, WalletSession> onComplete; // called with the new wallet's name and its already-open session
    private final Runnable onCancel;

    private final CardLayout stepLayout = new CardLayout();
    private final JPanel stepArea = new JPanel(stepLayout);

    private final WalletNameStep nameStep;
    private final WalletPasswordStep passwordStep;
    private final SeedStep seedStep;

    private static final String[] STEPS = {"NAME", "PASSWORD", "SEED"};
    private int currentStep = 0;

    private String mnemonic; // generated once, the first time SEED is entered; reused across back/forward navigation

    private final JButton backButton = new WalletButton("Back", WalletButton.Style.SECONDARY);
    private final JButton nextButton = new WalletButton("Next", WalletButton.Style.PRIMARY);
    private final JButton cancelButton = new WalletButton("Cancel", WalletButton.Style.SECONDARY);

    public CreateWalletWizardPanel(WalletStorage storage, BiConsumer<String, WalletSession> onComplete, Runnable onCancel) {
        this.storage = storage;
        this.onComplete = onComplete;
        this.onCancel = onCancel;

        setLayout(new BorderLayout());
        setBackground(UIManager.getColor("EasyHandshakeWallet.background"));

        nameStep = new WalletNameStep(storage);
        passwordStep = new WalletPasswordStep();
        seedStep = new SeedStep();

        stepArea.setOpaque(false);
        stepArea.add(nameStep, "NAME");
        stepArea.add(passwordStep, "PASSWORD");
        stepArea.add(seedStep, "SEED");

        add(stepArea, BorderLayout.CENTER);
        add(buildButtonRow(), BorderLayout.SOUTH);

        showStep(0);
    }

    private JPanel buildButtonRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(16, 32, 28, 32));

        // Font already set by WalletButton itself -- no need to
        // reapply it here for each button.

        cancelButton.addActionListener(e -> onCancel.run());
        backButton.addActionListener(e -> showStep(currentStep - 1));
        nextButton.addActionListener(e -> handleNext());

        row.add(cancelButton);
        row.add(backButton);
        row.add(nextButton);
        return row;
    }

    private void showStep(int index) {
        currentStep = index;
        stepLayout.show(stepArea, STEPS[index]);
        backButton.setVisible(index > 0);
        nextButton.setText(index == STEPS.length - 1 ? "Finish" : "Next");

        // The seed is generated exactly once, the first time this step
        // is actually reached -- not regenerated on every visit, which
        // would mean going Back then Next again silently showed a
        // *different* phrase than the one already written down.
        if (index == 2 && mnemonic == null) {
            mnemonic = Bip39.generate(256); // 24 words, matching real hsd's own default
            seedStep.showMnemonic(mnemonic);
        }
    }

    private void handleNext() {
        switch (currentStep) {
            case 0 -> {
                String error = nameStep.validateStep();
                if (error != null) { nameStep.showError(error); return; }
                showStep(1);
            }
            case 1 -> {
                String error = passwordStep.validateStep();
                if (error != null) { passwordStep.showError(error); return; }
                showStep(2);
            }
            case 2 -> finishCreation();
        }
    }

    private void finishCreation() {
        String name = nameStep.getWalletName();
        char[] password = passwordStep.getPassword();
        try {
            // BIP-39's own optional passphrase, not this wallet's
            // encryption password -- a genuinely separate concept (see
            // Bip39.mnemonicToSeed's own comment), so an empty string is
            // deliberate here, not a placeholder standing in for
            // something forgotten.
            byte[] seed = Bip39.mnemonicToSeed(mnemonic, "");
            // -1 = brand-new wallet: nothing can predate it, so scanning starts at the chain tip
            storage.createWallet(name, WalletStorage.CURRENCY_HNS, seed, new String(password), 0, -1);
            Arrays.fill(seed, (byte) 0);
            // Open the session right now, while the password the person just
            // typed is still in hand -- otherwise the wallet would sit
            // un-opened (no session, no sync) until they picked it from the
            // dropdown and typed the same password a second time.
            WalletSession session = WalletSession.open(storage, name, new String(password));
            onComplete.accept(name, session);
        } catch (SQLException | WalletCrypto.WrongPassphraseException ex) {
            seedStep.showError("Could not save this wallet: " + ex.getMessage());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    // ── Step 3: seed phrase ──────────────────────────────────────────

    private class SeedStep extends JPanel {
        private static final int GRID_COLUMNS = 8;
        private static final int GRID_ROWS = 3;
        private static final int GRID_HGAP = 10;
        private static final int GRID_VGAP = 8;

        private final JPanel content = new JPanel();
        private final JPanel wordGrid = new JPanel(new GridLayout(GRID_ROWS, GRID_COLUMNS, GRID_HGAP, GRID_VGAP));
        // No rows/columns given -- the zero-arg constructor, not
        // new JTextArea(4, 0) (a real, confirmed bug: a non-zero rows
        // value pins a MINIMUM preferred height based on that row
        // count, regardless of what the actually wrapped content needs.
        // That pin is exactly what was silently overriding every
        // width-based measurement below -- confirmed directly, this
        // component reported the same fixed height at every width tried
        // from 830px to 950px, for a phrase whose real wrapped content
        // only needed roughly 2 rows once it had a wide enough width to
        // wrap into. The earlier version of this same code partially
        // masked the bug: at the previous, narrower 6-column grid width,
        // several real phrases' actual content happened to need MORE
        // than the pinned 4 rows, so the true, larger content height
        // won out and looked correct by coincidence -- it only broke
        // visibly once a wider grid meant some phrases genuinely needed
        // fewer rows than what was pinned.
        private final JTextArea mnemonicArea = new JTextArea();
        private final JLabel errorLabel = new JLabel(" ");

        SeedStep() {
            setLayout(new GridBagLayout());
            setOpaque(false);

            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            // Width is set dynamically in showMnemonic(), once the real
            // word chips exist and their actual measured size is known
            // -- a fixed guess here would either waste space or, worse,
            // be too narrow for whichever words this particular phrase
            // happens to contain.

            JLabel title = new JLabel("Write down your recovery phrase");
            title.setFont(WalletFonts.headerSemiBold(20f));
            title.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);

            // JTextArea, not JLabel -- a plain JLabel never wraps long
            // text; its preferred width is the full, unwrapped string,
            // which is exactly what was dragging this whole panel wider
            // than the window and swallowing all its own padding.
            JTextArea warning = new JTextArea(
                    "Anyone with these 24 words can access this wallet's funds. Write them down "
                            + "and store them somewhere safe and offline -- never as a photo, a "
                            + "screenshot, or a file on this computer.");
            warning.setEditable(false);
            warning.setFocusable(false);
            warning.setLineWrap(true);
            warning.setWrapStyleWord(true);
            warning.setOpaque(false);
            warning.setFont(WalletFonts.body(13f));
            warning.setForeground(UIManager.getColor("EasyHandshakeWallet.danger"));
            warning.setAlignmentX(Component.LEFT_ALIGNMENT);
            warning.setBorder(new EmptyBorder(8, 0, 20, 0));

            wordGrid.setOpaque(false);
            wordGrid.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel copyLabel = new JLabel("Or copy the full phrase:");
            copyLabel.setFont(WalletFonts.body(12f));
            copyLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
            copyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            copyLabel.setBorder(new EmptyBorder(18, 0, 4, 0));

            JPanel copyRow = new JPanel(new BorderLayout(8, 0));
            copyRow.setOpaque(false);
            copyRow.setAlignmentX(Component.LEFT_ALIGNMENT);

            // Multi-line, not a single-line field -- so all 24 words are
            // visible at once rather than scrolled off to one side.
            mnemonicArea.setEditable(false);
            mnemonicArea.setLineWrap(true);
            mnemonicArea.setWrapStyleWord(true);
            mnemonicArea.setFont(WalletFonts.mono(13f));
            mnemonicArea.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
            mnemonicArea.setBackground(UIManager.getColor("EasyHandshakeWallet.panelBackground"));
            mnemonicArea.setBorder(BorderFactory.createCompoundBorder(
                    new javax.swing.border.LineBorder(UIManager.getColor("EasyHandshakeWallet.borderMetal"), 1),
                    new EmptyBorder(8, 10, 8, 10)));

            // A real Unicode glyph (two overlapping squares), not an
            // image and not the word "Copy" -- specifically to avoid
            // any chance of it being misread as one of the 24 actual
            // seed words sitting right above it in the same layout.
            JButton copyButton = new WalletButton("", WalletButton.Style.SECONDARY);
            copyButton.setToolTipText("Copy to clipboard");
            copyButton.setIcon(new CopyIcon(14,
                    UIManager.getColor("EasyHandshakeWallet.silver"),
                    UIManager.getColor("EasyHandshakeWallet.panelBackground")));
            copyButton.setBorder(BorderFactory.createCompoundBorder(
                    new javax.swing.border.LineBorder(UIManager.getColor("EasyHandshakeWallet.borderMetal"), 1),
                    new EmptyBorder(9, 12, 9, 12)));
            copyButton.addActionListener(e -> {
                java.awt.datatransfer.StringSelection selection =
                        new java.awt.datatransfer.StringSelection(mnemonicArea.getText());
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            });

            // Wrapped so the button itself stays compact and top-aligned
            // rather than BorderLayout.EAST stretching it to the full
            // height of the multi-line text area next to it.
            JPanel copyButtonWrapper = new JPanel(new BorderLayout());
            copyButtonWrapper.setOpaque(false);
            copyButtonWrapper.add(copyButton, BorderLayout.NORTH);

            copyRow.add(mnemonicArea, BorderLayout.CENTER);
            copyRow.add(copyButtonWrapper, BorderLayout.EAST);

            errorLabel.setFont(WalletFonts.body(12f));
            errorLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.danger"));
            errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            errorLabel.setBorder(new EmptyBorder(16, 0, 0, 0));

            content.add(title);
            content.add(warning);
            content.add(wordGrid);
            content.add(copyLabel);
            content.add(copyRow);
            content.add(errorLabel);
            add(content);
        }

        void showMnemonic(String mnemonic) {
            wordGrid.removeAll();
            String[] words = mnemonic.split(" ");

            // Each chip's real preferred size, measured from its actual
            // font metrics and content -- not a guessed pixel width --
            // so the grid is sized to fit exactly what these specific
            // 24 words need, whether this phrase happens to contain
            // "id" or "encouragement".
            //
            // SAFETY_MARGIN_PX exists because a real, reported case
            // showed a word ("install") still getting ellipsis-
            // truncated despite this same measure-then-size approach --
            // getPreferredSize() here runs before this chip has ever
            // been added to a realized, displayable window, and Swing's
            // font-metrics calculation in that state can come out
            // slightly narrower than what the same text actually
            // measures once truly on screen, especially for a custom,
            // programmatically-loaded font. A fixed pixel margin is a
            // pragmatic, defensive fix for that gap rather than a
            // guarantee tied to a fully-confirmed root cause -- this
            // sandbox can't render the real, on-screen result to verify
            // the exact mechanism directly.
            final int SAFETY_MARGIN_PX = 14;

            int maxChipWidth = 0, maxChipHeight = 0;
            JComponent[] chips = new JComponent[words.length];
            for (int i = 0; i < words.length; i++) {
                chips[i] = buildWordChip(i + 1, words[i]);
                Dimension pref = chips[i].getPreferredSize();
                maxChipWidth = Math.max(maxChipWidth, pref.width);
                maxChipHeight = Math.max(maxChipHeight, pref.height);
            }
            maxChipWidth += SAFETY_MARGIN_PX;
            for (JComponent chip : chips) wordGrid.add(chip);

            int gridWidth = maxChipWidth * GRID_COLUMNS + GRID_HGAP * (GRID_COLUMNS - 1);
            int gridHeight = maxChipHeight * GRID_ROWS + GRID_VGAP * (GRID_ROWS - 1);
            Dimension gridSize = new Dimension(gridWidth, gridHeight);
            wordGrid.setPreferredSize(gridSize);
            wordGrid.setMaximumSize(gridSize);

            // content's own width follows the grid's real, measured
            // width by default -- but the copy-able text area below it
            // is allowed to stretch wider than the grid on its own,
            // specifically to fit its content in 2 lines rather than
            // more, capped so it never approaches the window's edges.
            content.setMaximumSize(new Dimension(gridWidth, Integer.MAX_VALUE));

            mnemonicArea.setText(mnemonic);
            // Explicit, not relying on setText()'s own default -- a
            // defensive fix regardless of the exact original cause,
            // given a scrolled/misplaced caret was the leading theory
            // for why the displayed phrase could ever appear to differ
            // from the actual one.
            mnemonicArea.setCaretPosition(0);

            // The minimum width this specific phrase actually needs to
            // wrap to 2 lines or fewer -- measured directly by trying
            // progressively wider values, not assumed from a fixed
            // pixel guess, the same "measure the real content" approach
            // used for the word grid itself above. Capped at
            // MAX_TEXT_AREA_WIDTH so it can never grow all the way to
            // the window's own edges regardless of how wide a
            // particular phrase's words happen to be.
            final int MAX_TEXT_AREA_WIDTH = 1000;
            final int WIDTH_STEP_PX = 20;
            int lineHeight = mnemonicArea.getFontMetrics(mnemonicArea.getFont()).getHeight();

            // A fresh, throwaway probe for the width search itself,
            // not the real mnemonicArea field -- confirmed directly
            // (comparing production output against an isolated,
            // equivalent standalone test) that repeatedly querying
            // getPreferredSize() on the SAME JTextArea instance across
            // several different setSize() calls does not reliably
            // recompute each time the way a fresh instance measured
            // once does. A brand-new component per width avoids
            // whatever internal caching causes that, at the cost of
            // discarding a few short-lived objects during this one
            // search -- a fine trade for correctness here.
            int areaWidth = gridWidth;
            int wrappedHeight;
            while (true) {
                JTextArea probe = new JTextArea(mnemonic);
                probe.setLineWrap(true);
                probe.setWrapStyleWord(true);
                probe.setFont(mnemonicArea.getFont());
                probe.setSize(areaWidth, Short.MAX_VALUE);
                wrappedHeight = probe.getPreferredSize().height;
                int lines = Math.round((float) wrappedHeight / lineHeight);
                if (lines <= 2 || areaWidth >= MAX_TEXT_AREA_WIDTH) break;
                areaWidth = Math.min(areaWidth + WIDTH_STEP_PX, MAX_TEXT_AREA_WIDTH);
            }

            // mnemonicArea's own border insets (a 1px line border plus
            // 8px padding, top and bottom) added on top of the raw text
            // height -- a real, confirmed bug otherwise: the probe used
            // for measurement above has no border at all, so wrappedHeight
            // only ever accounted for the bare text content. Setting that
            // as the real, bordered field's final height left no room for
            // its own border/padding to fit inside it, squeezing the
            // actual text area down and clipping descenders (g, p, y) on
            // the second line right at the bottom edge.
            Insets areaInsets = mnemonicArea.getInsets();
            Dimension areaSize = new Dimension(areaWidth, wrappedHeight + areaInsets.top + areaInsets.bottom);
            mnemonicArea.setPreferredSize(areaSize);
            mnemonicArea.setMaximumSize(areaSize);

            // content needs to be at least as wide as whichever of the
            // grid or the (possibly now wider) text area needs more
            // room, so neither one gets clipped by the other's width.
            content.setMaximumSize(new Dimension(Math.max(gridWidth, areaWidth), Integer.MAX_VALUE));

            content.revalidate();
            content.repaint();
        }

        private JComponent buildWordChip(int number, String word) {
            // Stacked (number above word), not side-by-side -- the
            // number is now small and tucked in the top-left corner,
            // "superscript"-style, and the word gets the chip's full
            // width to itself rather than sharing horizontal room with
            // the number.
            JPanel chip = new JPanel();
            chip.setLayout(new BoxLayout(chip, BoxLayout.Y_AXIS));
            chip.setOpaque(true);
            chip.setBackground(UIManager.getColor("EasyHandshakeWallet.panelBackground"));
            chip.setBorder(BorderFactory.createCompoundBorder(
                    new javax.swing.border.LineBorder(UIManager.getColor("EasyHandshakeWallet.borderMetal"), 1),
                    new EmptyBorder(4, 8, 6, 8)));

            JLabel numberLabel = new JLabel(String.valueOf(number));
            numberLabel.setFont(WalletFonts.mono(9f));
            numberLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
            numberLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel wordLabel = new JLabel(word);
            wordLabel.setFont(WalletFonts.mono(13f));
            wordLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
            wordLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            wordLabel.setBorder(new EmptyBorder(2, 0, 0, 0));

            chip.add(numberLabel);
            chip.add(wordLabel);
            return chip;
        }

        void showError(String message) {
            errorLabel.setText(message);
        }
    }
}