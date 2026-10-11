package handshake.wallet.gui.panels;

import handshake.wallet.Bip39;
import handshake.wallet.gui.theme.WalletFonts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * The "enter your recovery phrase" step -- the restore flow's
 * counterpart to the create flow's seed-display step, just in reverse:
 * typed or pasted in rather than generated and shown. A single,
 * editable text area rather than 24 individual boxes, deliberately --
 * this project's own earlier discussion on the create flow's copy
 * field established a real, stated preference for paste over manual
 * typing, and that preference applies at least as strongly here, if
 * not more (a mistyped restore doesn't just cost a retry, it silently
 * derives the wrong wallet entirely unless caught).
 */
public class RecoveryPhraseStep extends JPanel {

    private final JTextArea phraseArea = new JTextArea();
    private final JLabel errorLabel = new JLabel(" ");

    public RecoveryPhraseStep() {
        setLayout(new GridBagLayout());
        setOpaque(false);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setMaximumSize(new Dimension(620, Integer.MAX_VALUE));

        JLabel title = new JLabel("Enter your recovery phrase");
        title.setFont(WalletFonts.headerSemiBold(20f));
        title.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel hint = new JLabel("Paste or type the 12, 15, 18, 21, or 24 words, separated by spaces.");
        hint.setFont(WalletFonts.body(13f));
        hint.setForeground(UIManager.getColor("EasyHandshakeWallet.silver"));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(new EmptyBorder(6, 0, 20, 0));

        phraseArea.setLineWrap(true);
        phraseArea.setWrapStyleWord(true);
        phraseArea.setFont(WalletFonts.mono(13f));
        phraseArea.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
        phraseArea.setBackground(UIManager.getColor("EasyHandshakeWallet.panelBackground"));
        phraseArea.setBorder(BorderFactory.createCompoundBorder(
                new javax.swing.border.LineBorder(UIManager.getColor("EasyHandshakeWallet.borderMetal"), 1),
                new EmptyBorder(8, 10, 8, 10)));
        phraseArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        // A fixed size appropriate for a typical 24-word phrase at this
        // width, not the same dynamic per-content measurement the
        // create flow's display-only version uses -- there's no fixed,
        // known-in-advance content to measure here the same way; this
        // field starts empty and its content only exists once someone
        // has actually typed or pasted into it.
        phraseArea.setPreferredSize(new Dimension(620, 90));
        phraseArea.setMaximumSize(new Dimension(620, 90));

        errorLabel.setFont(WalletFonts.body(12f));
        errorLabel.setForeground(UIManager.getColor("EasyHandshakeWallet.danger"));
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        errorLabel.setBorder(new EmptyBorder(10, 0, 0, 0));

        content.add(title);
        content.add(hint);
        content.add(phraseArea);

        content.add(errorLabel);
        add(content);
    }

    public String getPhrase() {
        return phraseArea.getText();
    }

    /** Returns a human-readable error, or null if the phrase is valid.
     *  Bip39.isValid() alone handles length (12/15/18/21/24 words),
     *  wordlist membership, and checksum verification all at once --
     *  no need to duplicate any of that logic here, and no need to
     *  normalize whitespace first either (Bip39's own normalize(),
     *  called internally, already trims and collapses whitespace,
     *  including any newlines a multi-line paste might contain). */
    public String validateStep() {
        String phrase = getPhrase();
        if (phrase.isBlank()) return "Enter your recovery phrase.";
        if (!Bip39.isValid(phrase)) return "This doesn't look like a valid recovery phrase. Check for typos and try again.";
        return null;
    }

    public void showError(String message) {
        errorLabel.setText(message);
    }
}