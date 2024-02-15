package bguspl.set.ex;

import bguspl.set.Env;


import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

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
        WAIT_DEALER,
        POINT_FREEZE
    }
    private volatile PlayerState state;

    /**
     * The queue of key presses (used by the AI player).
     */
    private final BlockingQueue<Integer> queue;

    /**
     * The dealer object.
     */
    private final Dealer dealer;

    private static final long FREEZE_MILLIS = 900L;

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

        this.queue = new ArrayBlockingQueue<>(env.config.featureSize);
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
            switch (state) {
                case PLAY:
                    performAction();
                    break;
                case POINT_FREEZE:
                    freeze(env.config.pointFreezeMillis);
                    break;
                case PENALTY_FREEZE:
                    freeze(env.config.penaltyFreezeMillis);
                    break;
                default:
                    break;
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Called by the player thread.
     * Freezes the player for a given number of milliseconds & updates the UI.
     * @param millis - how long to freeze the player.
     * @implNote - this is a busy wait function.
     *
     * @pre  - playerState == POINT_FREEZE || playerState == PENALTY_FREEZE
     * @post - playerState == PLAY
     */
    private void freeze(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (end - System.currentTimeMillis() > 0 && !terminate) {
            env.ui.setFreeze(id, end - System.currentTimeMillis());
            try {
                Thread.currentThread().sleep(FREEZE_MILLIS);
            } catch (InterruptedException ignored) {
                env.logger.warning(
                        playerThread.getName() + " was interrupted, during a freeze.");
            }
        }
        env.ui.setFreeze(id, 0);
        state = PlayerState.PLAY;
    }

    /**
     * Called by the player thread.
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
                queue.offer(random.nextInt(maxSlot));
                synchronized (this) {
                    notifyAll();
                    try {
                        wait();
                    } catch (InterruptedException e) { }
                }
            }

            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called by the dealer thread.
     * Called when the game should be terminated.
     *
     * @pre  - the player & AI threads are running & terminate == false.
     * @post - the player & AI threads are interrupted & terminate == true.
     */
    public void terminate() {
        env.logger.info("Terminating " + playerThread.getName() + "...");
        terminate = true;
        playerThread.interrupt();
        if(!human) aiThread.interrupt();
    }

    /**
     * Called by the EventListener thread and the AI thread.
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if(state == PlayerState.PLAY) {
            boolean changed = queue.offer(slot);
            // Optimize the synchronization.
            if (changed) {
                synchronized (this) {
                    notifyAll();
                }
            }
        }
    }

    /**
     * Called by the player thread.
     * Dispatches the action in the queue.
     *
     * @pre - the queue is not empty.
     * @post - the queue is empty.
     */
    private synchronized void performAction() {
        while (!queue.isEmpty()) {
            boolean set = dealer.dispatchAction(this, queue.poll());
            if (set) {
                queue.clear();
                state = PlayerState.WAIT_DEALER;
            }
        }
        notifyAll();
        try {
            wait();
        } catch (InterruptedException ignore) {
        }
    }

    /**
     * Called by the dealer thread.
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public synchronized void point() {
        // Change player state to freeze, and give him a point.
        state = PlayerState.POINT_FREEZE;
        // Since player thread waits for decision.
        notifyAll();
        // This part is just for demonstration in the unit tests
        int ignored = table.countCards();
        // This is already increments the score.
        env.ui.setScore(id, ++score);
    }

    /**
     * Called by the dealer thread.
     * Penalize a player and perform other related actions.
     *
     * @pre - playerState == WAIT_DEALER
     * @post - playerState == PENALTY_FREEZE
     */
    public synchronized void penalty() {
        // Change the player state to freeze, and wake him up since decision was accepted.
        state = PlayerState.PENALTY_FREEZE;
        notifyAll();
    }

    /**
     * Called by the dealer thread.
     * If a set request was invalidated return to play state, so they can keep listening to key presses.
     *
     * @pre - playerState == WAIT_DEALER
     * @post - playerState == PLAY
     */
    public synchronized void irrelevantRequest() {
        state = PlayerState.PLAY;
        notifyAll();
    }

    /**
     * @return the player's score.
     *
     * @pre & @post - score >= 0
     */
    public int score() {
        return score;
    }

    @Override
    public String toString() {
        return "Player " + (this.id + 1);
    }
}
