package handshake.wallet.gui.panels;

import handshake.wallet.Bip39;
import handshake.wallet.WalletStorage;
import handshake.wallet.gui.theme.WalletButton;
import handshake.wallet.gui.theme.WalletFonts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.function.BiConsumer;
import handshake.wallet.WalletCrypto;
import handshake.wallet.WalletSession;

/**
 * The "Restore a previous wallet" flow -- name, enter the existing
 * recovery phrase, set a password. Deliberately the same shared
 * button-row and step-navigation structure as CreateWalletWizardPanel
 * (see that class for the reasoning), reusing its exact WalletNameStep
 * and WalletPasswordStep components rather than duplicating them --
 * the only real difference between the two flows is whether the
 * middle step generates a phrase to show, or asks for one to enter.
 *
 * The recovery-phrase step comes before the password step, not after
 * -- validating the harder, more error-prone input first means a typo
 * gets caught before someone's invested effort setting up a password,
 * rather than only surfacing at the very end.
 */
public class RestoreWalletWizardPanel extends JPanel {

    private final WalletStorage storage;
    private final BiConsumer<String, WalletSession> onComplete;
    private final Runnable onCancel;

    private final CardLayout stepLayout = new CardLayout();
    private final JPanel stepArea = new JPanel(stepLayout);

    private final WalletNameStep nameStep;
    private final RecoveryPhraseStep recoveryPhraseStep;
    private final WalletPasswordStep passwordStep;

    private static final String[] STEPS = {"NAME", "PHRASE", "PASSWORD"};
    private int currentStep = 0;

    private final JButton backButton = new WalletButton("Back", WalletButton.Style.SECONDARY);
    private final JButton nextButton = new WalletButton("Next", WalletButton.Style.PRIMARY);
    private final JButton cancelButton = new WalletButton("Cancel", WalletButton.Style.SECONDARY);

    public RestoreWalletWizardPanel(WalletStorage storage, BiConsumer<String, WalletSession> onComplete, Runnable onCancel) {
        this.storage = storage;
        this.onComplete = onComplete;
        this.onCancel = onCancel;

        setLayout(new BorderLayout());
        setBackground(UIManager.getColor("EasyHandshakeWallet.background"));

        nameStep = new WalletNameStep(storage);
        recoveryPhraseStep = new RecoveryPhraseStep();
        passwordStep = new WalletPasswordStep();

        stepArea.setOpaque(false);
        stepArea.add(nameStep, "NAME");
        stepArea.add(recoveryPhraseStep, "PHRASE");
        stepArea.add(passwordStep, "PASSWORD");

        add(stepArea, BorderLayout.CENTER);
        add(buildButtonRow(), BorderLayout.SOUTH);

        showStep(0);
    }

    private JPanel buildButtonRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(16, 32, 28, 32));

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
    }

    private void handleNext() {
        switch (currentStep) {
            case 0 -> {
                String error = nameStep.validateStep();
                if (error != null) { nameStep.showError(error); return; }
                showStep(1);
            }
            case 1 -> {
                String error = recoveryPhraseStep.validateStep();
                if (error != null) { recoveryPhraseStep.showError(error); return; }
                showStep(2);
            }
            case 2 -> finishRestore();
        }
    }

    private void finishRestore() {
        String passwordError = passwordStep.validateStep();
        if (passwordError != null) { passwordStep.showError(passwordError); return; }

        String name = nameStep.getWalletName();
        String phrase = recoveryPhraseStep.getPhrase();
        char[] password = passwordStep.getPassword();
        try {
            byte[] seed = Bip39.mnemonicToSeed(phrase, "");
            storage.createWallet(name, WalletStorage.CURRENCY_HNS, seed, new String(password), 0);
            Arrays.fill(seed, (byte) 0);
            // Open the session now, with the password just typed, so the
            // restored wallet starts syncing immediately instead of waiting
            // for a second, separate unlock.
            WalletSession session = WalletSession.open(storage, name, new String(password));
            onComplete.accept(name, session);
        } catch (SQLException | WalletCrypto.WrongPassphraseException ex) {
            passwordStep.showError("Could not restore this wallet: " + ex.getMessage());
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}