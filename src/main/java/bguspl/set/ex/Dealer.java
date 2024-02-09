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
     * The request queue.
     */
    private final Queue<Request> requestQueue;

    /**
     * A request to review a set.
     */
    private static class Request {
        public final Player player;
        public final Queue<Integer> set;

        Request(Player player, Queue<Integer> set) {
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
     * The slots to remove from the table.
     */
    private int[] cardsToRemove;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());

        requestQueue = new LinkedList<>();
        deletedSlots = new LinkedList<>();

        // Add all the slots to the deleted slots queue:
        for(int i = 0; i < table.slotCount(); i++) {
            deletedSlots.offer(i);
        }
    }

    /**
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

        // So we won't start in the year 3024.
        updateTimerDisplay(true);

        // Main dealer loop
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop(); // Either here or in the timerLoop you should check for the???
            updateTimerDisplay(true);
            validateSet();
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
            validateSet();
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        Arrays.stream(players).forEach(Player::terminate);
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
        if(cardsToRemove != null)
            Arrays.stream(cardsToRemove).forEach(i -> removeCardHelper(table.getSlotOfCard(i)));
        cardsToRemove = null;
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        if(reshuffleTime >= System.currentTimeMillis()) {
            env.logger.info("Shuffling deck...");
            Collections.shuffle(deck);
        }
        boolean placed = true;
        while(!deletedSlots.isEmpty() && placed) {
            placed = false;
            int slot = deletedSlots.poll();
            for(int i = 0; i < deck.size() && !placed; i++) {
                if(!table.containsCard(deck.get(i))) {
                    table.placeCard(deck.get(i), slot);
                    placed = true;
                }
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        // If there are requests, treat them without delay
        if(requestQueue.isEmpty()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset)
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;

        boolean warn = reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis;
        env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), warn);
    }

    /**
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
            tokens.forEach(playerId -> players[playerId].removeToken(slot));
            table.removeCard(slot);
        }
    }

    /**
     * Removes a card from the table and the deck.
     * Also updates all the players who tokenized this slot.
     * @param slot - the slot from which to remove the card.
     */
    private void removeCardHelper(int slot) {
        removeCardHelper(slot, false);
    }

    /**
     * Called when a player requests a set.
     *
     * @param player - the player that requested the set.
     * @param set    - the set of cards that the player requested.
     */
    public void requestSet(Player player, Queue<Integer> set) {
        synchronized (requestQueue) {
            requestQueue.offer(new Request(player, set));
            dealerThread.interrupt(); // Wake up dealer in case it's sleeping.
        }
    }

    /**
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
            while(request.set.size() != 3 && !requestQueue.isEmpty()) {
                request = requestQueue.poll();
            }

            // If we found a request with 3 tokens, validate it
            if(request.set.size() == 3) {
                int[] cards = request.set.stream()
                        .mapToInt(table::getCardAtSlot)
                        .toArray();
                if(env.util.testSet(cards)) {
                    request.player.point();
                    // Set the cards for removal in the next iteration.
                    cardsToRemove = cards;
                } else {
                    request.player.penalty();
                }
            }
        }
    }

}
