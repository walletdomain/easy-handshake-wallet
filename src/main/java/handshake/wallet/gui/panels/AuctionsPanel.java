package handshake.wallet.gui.panels;

import handshake.wallet.WalletAuctionMonitor;
import handshake.wallet.WalletSession;
import handshake.wallet.gui.theme.WalletButton;
import handshake.wallet.gui.theme.WalletFonts;
import handshake.wallet.gui.theme.WalletPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Auctions" screen: the wallet's active auctions and the "Place new bid"
 * button (moved here from the dashboard). An OPEN or BID this wallet makes
 * also shows at the top of the dashboard's Recent activity.
 */
public class AuctionsPanel extends JPanel {

    // Handshake mainnet auction timing, in blocks -- the same constants used by
    // WalletAuctionMonitor.checkRevealDeadline() and WalletAuctionEngine:
    // 37 blocks from OPEN to bidding start, 720-block bidding period,
    // 1440-block reveal period. 144 blocks is about a day.
    private static final int OPEN_TO_BIDDING_DELAY = 37;
    private static final int BIDDING_PERIOD = 720;
    private static final int REVEAL_PERIOD = 1440;
    private static final int BLOCKS_PER_DAY = 144;

    private final JPanel auctionRows;

    public AuctionsPanel() {
        setLayout(new BorderLayout());
        setBackground(UIManager.getColor("EasyHandshakeWallet.background"));
        setBorder(new EmptyBorder(28, 32, 28, 32));

        JLabel pageTitle = new JLabel("Auctions");
        pageTitle.setFont(WalletFonts.headerSemiBold(22f));
        pageTitle.setForeground(UIManager.getColor("EasyHandshakeWallet.textPrimary"));
        pageTitle.setBorder(new EmptyBorder(0, 0, 20, 0));
        add(pageTitle, BorderLayout.NORTH);

        WalletPanel auctionsPanel = new WalletPanel("Active auctions");
        auctionRows = new JPanel();
        auctionRows.setLayout(new BoxLayout(auctionRows, BoxLayout.Y_AXIS));
        auctionRows.setOpaque(false);
        auctionsPanel.setContent(auctionRows);
        add(auctionsPanel, BorderLayout.CENTER);
    }

    /** Rebuilds the list from the session's current auction state. */
    public void refresh(WalletSession session) {
        rebuildAuctions(session.auctionMonitor.watchedAuctionsSnapshot(), session.headerChain.tipHeight());
        revalidate();
        repaint();
    }

    private void rebuildAuctions(List<WalletAuctionMonitor.AuctionState> auctions, int currentHeight) {
        auctionRows.removeAll();

        List<WalletAuctionMonitor.AuctionState> active = new ArrayList<>();
        for (WalletAuctionMonitor.AuctionState state : auctions) {
            int revealCloseHeight = state.openHeight + OPEN_TO_BIDDING_DELAY + BIDDING_PERIOD + REVEAL_PERIOD;
            if (currentHeight < revealCloseHeight) active.add(state); // "active" means not yet past its own reveal close
        }

        if (active.isEmpty()) {
            auctionRows.add(DashboardPanel.emptyStateLabel("No active auctions."));
        } else {
            for (WalletAuctionMonitor.AuctionState state : active) {
                auctionRows.add(DashboardPanel.buildRow(state.name, auctionPhaseLabel(state, currentHeight),
                        DashboardPanel.formatHns(auctionDisplayAmount(state)), true));
            }
        }

        auctionRows.add(Box.createVerticalStrut(8));
        JButton bidButton = new WalletButton("Place new bid", WalletButton.Style.PRIMARY);
        bidButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        bidButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        auctionRows.add(bidButton);
    }

    /** "bidding · 3d left" / "reveal · 1d left" -- the same
     *  biddingCloseHeight/revealCloseHeight math already established in
     *  WalletAuctionMonitor.checkRevealDeadline(), not new logic. */
    private String auctionPhaseLabel(WalletAuctionMonitor.AuctionState state, int currentHeight) {
        int biddingCloseHeight = state.openHeight + OPEN_TO_BIDDING_DELAY + BIDDING_PERIOD;
        int revealCloseHeight = biddingCloseHeight + REVEAL_PERIOD;
        String phase;
        int blocksLeft;
        if (currentHeight < biddingCloseHeight) {
            phase = "bidding";
            blocksLeft = biddingCloseHeight - currentHeight;
        } else {
            phase = "reveal";
            blocksLeft = revealCloseHeight - currentHeight;
        }
        int daysLeft = Math.max(0, blocksLeft) / BLOCKS_PER_DAY;
        return phase + " \u00B7 " + daysLeft + "d left";
    }

    /** During bidding, real bid values are blinded -- nothing on chain
     *  reveals what anyone (including this wallet) actually bid until
     *  the reveal phase, so the only honestly-known amount is this
     *  wallet's OWN committed lockup, if it has a pending bid on this
     *  name. Once reveals start coming in, the actual highest revealed
     *  value is real, public information and a more meaningful number
     *  to show than a stale lockup. */
    private long auctionDisplayAmount(WalletAuctionMonitor.AuctionState state) {
        return state.highestRevealed >= 0 ? state.highestRevealed : 0;
    }

}