package bguspl.set.ex;

import bguspl.set.Env;


import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * The state determines what's the player's state.
     */
    private enum PlayerState {
        PLAY,
        PENALTY_FREEZE,
        POINT_FREEZE
    }
    private volatile PlayerState state;

    /**
     * The queue of key presses (used by the AI player).
     */
    private final Queue<Integer> queue;

    /**
     * The dealer object.
     */
    private final Dealer dealer;

    /**
     * The maximum number of key presses that can be processed at the time in the queue.
     * This was not mentioned as part of the configuration field that we need to support for the bonus.
     */
    private static final int MAX_KEY_PRESSES = 3;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.queue = new LinkedList<>();
        this.state = null;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        // Set the state to play, now we're not blocking this thread.
        this.state = PlayerState.PLAY;

        while (!terminate) {
            switch(state) {
                case PLAY:
                    // DO NOTHING
                    break;
                case POINT_FREEZE:
                    // Empty the queue.
                    synchronized (queue) { queue.clear(); }
                    try {
                        playerThread.sleep(env.config.pointFreezeMillis);
                    } catch (InterruptedException e) {
                        env.logger.warning(playerThread.getName() + " was interrupted, during point freeze.");
                    } finally {
                        state = PlayerState.PLAY;
                    }
                    break;
                case PENALTY_FREEZE:
                    // DO NOTHING
                    try {
                        playerThread.sleep(env.config.penaltyFreezeMillis);
                    } catch (InterruptedException e) {
                        env.logger.warning(playerThread.getName() + " was interrupted, during penalty freeze.");
                    } finally {
                        state = PlayerState.PLAY;
                    }
                    break;
                default:
                    env.logger.warning(
                            "Player " + playerThread.getName() + "  entered illegal state");
                    break;
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

            //To simulate key presses:
            Random random = new Random();
            int maxSlot = env.config.columns * env.config.rows;

            while (!terminate) {
                if(state==PlayerState.PLAY)
                    synchronized (queue) {
                        if(queue.size() < MAX_KEY_PRESSES) {
                            keyPressed(random.nextInt(maxSlot));
                        } else {
                            if(!queue.isEmpty()) {
                                queue.poll();
                            }
                        }
                    }
                // Why was it needed?
//                try {
//                    synchronized (this) { wait(); }
//                } catch (InterruptedException ignored) { }
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        env.logger.info("Terminating " + playerThread.getName() + "...");
        terminate = true;
        playerThread.interrupt();
        if(!human) aiThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // Ignore presses if player is frozen.
        synchronized (queue) {
            if (state == PlayerState.PLAY) {
                // To prevent redundant calls to the dealer.
                boolean changed = false;
                try {
                    // Handles removing / placing cards, the order of the statements is important
                    // since table.removeToken throws Exception.
                    if (!table.removeToken(id, slot) && !queue.remove(slot) && queue.size() < MAX_KEY_PRESSES) {
                        table.placeToken(id, slot);
                        queue.add(slot);
                        changed = true;
                    }
                } catch (IllegalStateException cardRemoved) {
                    env.logger.warning("Player " + id + " tried to place token on empty slot");
                }
                // Before showcasing on GitHub return this mess to the main loop, this is here
                // Just because of the assignment requirements.
                if (queue.size() == MAX_KEY_PRESSES && changed) {
                    try {
                        // Requesting the dealer to set the player's state.
                        dealer.requestSet(this, queue);
                        playerThread.wait();
                        if(!human) aiThread.wait();
                    } catch (InterruptedException e) {
                        env.logger.warning(playerThread.getName()
                                + " was interrupted, during waiting to the dealer.");
                    }
                }
            }
        }
    }

    /**
     * Used by the dealer thread, in-case a card was removed.
     * @param slot - the slot from which to remove the token.
     */
    public void removeToken(int slot) {
        synchronized (queue) {
            queue.remove(slot);
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // Change player state to freeze, and give him a point.
        state = PlayerState.POINT_FREEZE;
        // Since player thread waits for decision.
        synchronized (this) {
            notifyAll();
        }
        // This part is just for demonstration in the unit tests
        int ignored = table.countCards();
        // This is already increments the score.
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // Change the player state to freeze, and wake him up since decision was accepted.
        state = PlayerState.PENALTY_FREEZE;
        synchronized (this) {
            notifyAll();
        }
    }

    public int score() {
        return score;
    }
}
