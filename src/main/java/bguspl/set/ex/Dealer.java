package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
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

    /**
     * Player token count.
     */
    private List<Integer>[] playerTokens;

    /**
     * The request queue.
     */
    private final Queue<Request> requestQueue;

    /**
     * A request to review a set.
     */
    private static class Request {
        public final Player player;
        public final List<Integer> set;

        Request(Player player, List<Integer> set) {
            this.player = player;
            this.set = set;
        }
    }

    /**
     * The deleted slots queue.
     * Should remain undisclosed to multiple threads.
     */
    private final Queue<Integer> deletedSlots;

    /**
     * The dealer thread.
     */
    private Thread dealerThread;

    /**
     * Temporarily stores the slots to remove from the table.
     */
    private int[] cardsToRemove;

    /**
     * The maximum number of key presses that can be processed at the time in the queue.
     * This was not mentioned as part of the configuration field that we need to support for the bonus.
     */
    private final int MAX_KEY_PRESSES;

    /**
     * The different game types ( for bonus )
     * Normal - normal game mode, normal timer, reshuffles the board after set time (even if no sets)
     * No Timer - no timer, however makes sure there is always at least 1 set on the table
     * Display Last Action - timer displays last time a set was found, must always make sure there is a set on the table.
     */
    private enum GameType {
        NORMAL,
        NOTIMER,
        DISPLAYLASTACTION
    }

    private long lastActionTimestamp;

    /**
     * Indicates what the game type is, out of the three GameTypes: Normal, No Timer, Display Last Action.
     * Set in the config file at the start of the game.
     * Normal - normal game mode, normal timer, reshuffles the board after set time (even if no sets)
     * No Timer - no timer, however makes sure there is always at least 1 set on the table
     * Display Last Action - timer displays last time a set was found, must always make sure there is a set on the table.
     */
    private final GameType gameType;

    /**
     * Functional interface to run the timer loop according to the game type.
     * See the interface implementation below.
     */
    private final TimerLoop timerLoopFunc;

    @FunctionalInterface
    private interface TimerLoop { void run(); }

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());

        this.playerTokens = new ArrayList[players.length];
        MAX_KEY_PRESSES = env.config.featureSize;
        for (int i = 0; i < playerTokens.length; i++) {
            playerTokens[i] = new ArrayList<>(MAX_KEY_PRESSES);
        }
        requestQueue = new LinkedList<>();
        deletedSlots = new LinkedList<>();

        long turnTimeout = env.config.turnTimeoutMillis;

        // Setting the game type according to the turn time out in config file
        if (turnTimeout > 0) {
            gameType = GameType.NORMAL;
            timerLoopFunc = () -> {
                while (!terminate && System.currentTimeMillis() < reshuffleTime) {
                    sleepUntilWokenOrTimeout();
                    updateTimerDisplay(false);
                    validateSet();
                    removeCardsFromTable();
                    placeCardsOnTable();
                }
            };
            env.logger.info("Set game mode as: Normal");
        }
        else if (turnTimeout == 0) {
            gameType = GameType.DISPLAYLASTACTION;
            timerLoopFunc = () -> {
                while (!terminate) {
                    ensureLegalSetExists();
                    sleepUntilWokenOrTimeout();
                    updateTimerDisplay(false);
                    validateSet();
                    removeCardsFromTable();
                    placeCardsOnTable();
                }
            };
            env.logger.info("Set game mode as: Display last action");
        }
        else {
            gameType = GameType.NOTIMER;
            timerLoopFunc = () -> {
                while (!terminate) {
                    ensureLegalSetExists();
                    sleepUntilWokenOrTimeout();
                    validateSet();
                    removeCardsFromTable();
                    placeCardsOnTable();
                }
            };
            env.logger.info("Set game mode as: No timer");
        }

        // Add all the slots to the deleted slots queue:
        for(int i = 0; i < table.slotCount(); i++) {
            deletedSlots.offer(i);
        }
    }

    /**
     * Called by the main thread.
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

        // Save the dealer thread for interruption
        dealerThread = Thread.currentThread();

        // Start the player threads
        Arrays.stream(players)
                .forEach(player -> new Thread(player, "player " + (player.id + 1)).start());

        // Run the main loop
        runLoop();

        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The main loop for the dealer thread, changes according to the game mode
     */
    private void runLoop() {
        // First Shuffle.
        Collections.shuffle(deck);
        lastActionTimestamp = System.currentTimeMillis();
        // So we won't start in the year 3024.
        updateTimerDisplay(true);

        // Main dealer loop
        while (!terminate) {
            placeCardsOnTable();
            if(!terminate) {
                updateTimerDisplay(true);
                timerLoop();
                removeAllCardsFromTable();
            }
        }

    }

    /**
     * Called by the dealer thread.
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        timerLoopFunc.run();
    }

    /**
     * Called by the dealer thread.
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        for(int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
        }
    }

    /**
     * Called by the dealer thread.
     * Check if the game should be terminated or the game end conditions are met.
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Called by the dealer thread.
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if(cardsToRemove != null)
            Arrays.stream(cardsToRemove).forEach(i -> removeCardHelper(table.getSlotOfCard(i)));
        cardsToRemove = null;
    }

    //<editor-fold desc="Helper functions for other game modes">
    /**
     * Makes sure there is a legal set on the table, if there isn't redeals the table until there is
      */
    private void ensureLegalSetExists() {
        List<Integer> currentCardsOnTable = getCurrentCardsOnTable();
        while (env.util.findSets(currentCardsOnTable, 1).isEmpty()) {
            env.logger.info("No sets, redrawing...");
            reshuffleAndRedraw();
            currentCardsOnTable = getCurrentCardsOnTable();
            // Update last action
            lastActionTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * Gets all the current cards on the table
     * @return a list with the current cards on the table
     */
    private List<Integer> getCurrentCardsOnTable() {
        return Arrays.stream(table.slotToCard)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Removes all the cards, shuffles the deck and redeals
     */
    private void reshuffleAndRedraw() {
        removeAllCardsFromTable();
        Collections.shuffle(deck);
        placeCardsOnTable();
    }
    //</editor-fold>

    /**
     * Called by the dealer thread.
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        if(shouldFinish()) {
            terminate();
        } else {
            if (reshuffleTime <= System.currentTimeMillis()) {
                env.logger.info("Shuffling deck...");
                Collections.shuffle(deck);
            }
            boolean hints  = !deletedSlots.isEmpty();
            boolean placed = true;
            while (!deletedSlots.isEmpty() && placed) {
                placed = false;
                int slot = deletedSlots.poll();
                for (int i = 0; i < deck.size() && !placed; i++) {
                    if (!table.containsCard(deck.get(i))) {
                        table.placeCard(deck.get(i), slot);
                        placed = true;
                    }
                }
            }

            if(hints && env.config.hints) {
                table.hints();
            }
        }
    }

    /**
     * Called by the dealer thread.
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        // If there are requests, treat them without delay
        if(requestQueue.isEmpty()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) { }
        }
    }

    /**
     * Called by the dealer thread.
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (gameType == GameType.NOTIMER)
            return; // No timer in this game mode

        if(reset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        }

        if (gameType == GameType.NORMAL) {
            long disp = reshuffleTime - System.currentTimeMillis();
            boolean warn = disp < env.config.turnTimeoutWarningMillis;
            if (disp > 0) {
                env.ui.setCountdown(disp, warn);
            } else {
                env.ui.setCountdown(0, true);
            }
        }
        else if (gameType == GameType.DISPLAYLASTACTION) {
            long elapsed = System.currentTimeMillis() - lastActionTimestamp;
            env.ui.setElapsed(elapsed);
        }
    }

    /**
     * Called by the dealer thread.
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        deletedSlots.clear();
        int slots = table.slotCount();
        for(int i = 0; i < slots; i++) {
            removeCardHelper(i, true);
        }
    }

    /**
     * Called by the dealer thread.
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int max = -1;
        LinkedList<Integer> winners = new LinkedList<>();
        for(Player p : players) {
            if(p.score() > max) {
                max = p.score();
                winners.clear();
                winners.add(p.id);
            } else if(p.score() == max) {
                winners.add(p.id);
            }
        }
        env.ui.announceWinner(winners.stream().mapToInt(i -> i).toArray());
    }

    /**
     * Used by the player thread.
     * Removes / adds a token of a player and request set review accordingly.
     * @param player - the player which dispatched action.
     * @param slot   - the action is a token to place/remove.
     * @return       - true iff requestSet was called.
     */
    public boolean dispatchAction(Player player, int slot) {
        if(playerTokens[player.id].contains(slot)) {
            table.removeToken(player.id, slot);
            playerTokens[player.id].remove(Integer.valueOf(slot));
        } else if(playerTokens[player.id].size() < MAX_KEY_PRESSES) {
            try {
                table.placeToken(player.id, slot);
                playerTokens[player.id].add(slot);
                if (playerTokens[player.id].size() == MAX_KEY_PRESSES) {
                    requestSet(player, playerTokens[player.id]);
                    return true;
                }
            } catch (IllegalStateException ex) {
                env.logger.info(player + " tried to place a token on an empty slot");
            }
        } else {
            env.logger.info(player + " tried to add/remove token while not being able to.");
        }
        return false;
    }

    /**
     * Called by the dealer thread.
     * Removes a card from the table and the deck.
     * Also updates all the players who tokenized this slot.
     * @param slot - the slot from which to remove the card.
     * @param shuffle - true iff the deck is shuffled then the card should not be removed from the deck.
     */
    private void removeCardHelper(int slot, boolean shuffle) {
        deletedSlots.offer(slot);
        if(!shuffle) deck.remove(table.slotToCard[slot]);
        Vector<Integer> tokens = table.getPlayerTokens(slot);
        synchronized (tokens) {
            tokens.forEach(playerId -> {
                playerTokens[playerId].remove(Integer.valueOf(slot));
                players[playerId].irrelevantRequest();
            });
            table.removeCard(slot);
        }
    }

    /**
     * Called by the dealer thread.
     * Removes a card from the table and the deck.
     * Also updates all the players who tokenized this slot.
     * @param slot - the slot from which to remove the card.
     */
    private void removeCardHelper(int slot) {
        removeCardHelper(slot, false);
    }

    /**
     * Called by the player thread.
     * Called when a player requests a set.
     * @param player - the player that requested the set.
     * @param set    - the set of cards that the player requested.
     */
    public void requestSet(Player player, List<Integer> set) {
        synchronized (requestQueue) {
            requestQueue.offer(new Request(player, set));
            dealerThread.interrupt(); // Wake up dealer in case it's sleeping.
        }
    }

    /**
     * Called by the dealer thread.
     * Validates a set of cards from the request queue.
     * Rewards the player or penalizes it accordingly.
     */
    private void validateSet() {
        synchronized (requestQueue) {

            // If the queue is empty bye
            if(requestQueue.isEmpty())
                return;

            // Get the first request
            Request request = requestQueue.poll();
            // Since a request might be changed due to other request being handled, we need to make sure that
            // this request has 3 tokens.
            while(request.set.size() != MAX_KEY_PRESSES && !requestQueue.isEmpty()) {
                request = requestQueue.poll();
                request.player.irrelevantRequest();
            }

            // If we found a request with 3 tokens, validate it
            if(request.set.size() == MAX_KEY_PRESSES) {
                try {
                    int[] cards = request.set.stream()
                            .mapToInt(table::getCardAtSlot)
                            .toArray();
                    if (env.util.testSet(cards)) {
                        request.player.point();
                        // Set the cards for removal in the next iteration.
                        cardsToRemove = cards;
                        // Update last action since set was found.
                        lastActionTimestamp = System.currentTimeMillis();
                        updateTimerDisplay(true);
                    } else {
                        request.player.penalty();
                    }
                }
                // Only happens if the set request was sent after a different set request that caused
                // one of the cards in the current set request to be removed and not replaced, therefore pointing
                // to a null slot.
                catch (NullPointerException ex) { }
            }
        }
    }
}
