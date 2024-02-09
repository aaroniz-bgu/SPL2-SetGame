package bguspl.set.ex;

import bguspl.set.Env;

import java.util.List;
import java.util.Queue;
import java.util.Vector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        // TODO Start player threads
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop(); // Either here or in the timerLoop you should check for the
            updateTimerDisplay(false); // FIXME - shouldn't this be true?
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable(); // TODO REMOVE THIS?
            placeCardsOnTable(); // TODO REMOVE THIS?
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
        // Join with main thread
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        // TODO implement
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // TODO implement
        // TODO Reshuffle the deck
        // TODO Fill empty slots with cards from the deck
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        // TODO implement
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // TODO implement
        if(reset) reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // TODO implement
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
    }

    private void removeCardHelper(int slot) {
        Vector<Integer> tokens = table.getPlayerTokens(slot);
        synchronized (tokens) {
            tokens.forEach(playerId -> players[playerId].removeToken(slot));
            table.removeCard(slot);
        }
    }

    /**
     * Called when a player requests a set.
     *
     * @param player - the player that requested the set.
     * @param set    - the set of cards that the player requested.
     */
    public void requestSet(Player player, Queue<Integer> set) {
        // TODO ADD THE REQUEST TO A QUEUE OF HANDLED REQUESTS
    }


}
